"""Locate a JDK, build the sgmo Java helper, and run its subcommands.

The helper is a small jar built from ``patcher/java`` with pinned, checksummed Maven
dependencies (``patcher/java/deps.lock``). This module mirrors ``patcher/java/build.sh``
in pure Python so a player with only a JDK and Python can build and run it:

* find ``java`` / ``javac`` via ``JAVA_HOME`` or ``PATH`` and require Java >= 17,
* fetch any missing dependency jars with :mod:`urllib` and verify each SHA-256,
* compile with ``javac --release 17`` and pack a jar whose name embeds a hash of the
  sources, so an unchanged tree reuses the jar and a changed tree rebuilds,
* run a subcommand and return its parsed JSON output.

Standard library only.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path
from typing import Iterable, Sequence

#: Repository paths, relative to this file (``patcher/sgmo_patcher/javahelper.py``).
_JAVA_DIR = Path(__file__).resolve().parent.parent / "java"
_DEPS_LOCK = _JAVA_DIR / "deps.lock"
_SRC_DIR = _JAVA_DIR / "src"

_MIN_JAVA_MAJOR = 17


class HelperError(RuntimeError):
    """A Java helper invocation failed (non-zero, unexpected exit code)."""

    def __init__(self, message: str, *, returncode: int | None = None,
                 stdout: str = "", stderr: str = ""):
        super().__init__(message)
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr


class Result:
    """The outcome of one helper run."""

    def __init__(self, returncode: int, stdout: str, stderr: str):
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr

    def json(self):
        """Parse stdout as a single JSON document."""
        return json.loads(self.stdout)

    def json_lines(self) -> list:
        """Parse stdout as one JSON document per non-empty line."""
        return [json.loads(line) for line in self.stdout.splitlines() if line.strip()]


def cache_dir() -> Path:
    """The cache root ($SGMO_CACHE, else ~/.cache/sgm-offline)."""
    env = os.environ.get("SGMO_CACHE")
    return Path(env) if env else Path.home() / ".cache" / "sgm-offline"


# ---- JDK discovery ---------------------------------------------------------

def _tool(name: str) -> str:
    """Resolve a JDK tool (java/javac/jar), preferring $JAVA_HOME/bin."""
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / name
        if candidate.exists():
            return str(candidate)
    found = shutil.which(name)
    if found:
        return found
    raise HelperError(f"cannot find '{name}'; set JAVA_HOME or put a JDK on PATH")


def java_version(java: str | None = None) -> int:
    """Return the major version of the given (or located) ``java``."""
    java = java or _tool("java")
    proc = subprocess.run([java, "-version"], capture_output=True, text=True)
    # `java -version` writes to stderr, e.g. 'openjdk version "21.0.12.1" ...'.
    text = proc.stderr + proc.stdout
    m = re.search(r'version "(\d+)(?:\.(\d+))?', text)
    if not m:
        raise HelperError(f"cannot parse java version from: {text!r}")
    major = int(m.group(1))
    # Java 1.8 style -> 8; modern style -> first number.
    if major == 1 and m.group(2):
        major = int(m.group(2))
    return major


def check_java() -> None:
    """Raise unless a Java >= 17 runtime is available."""
    major = java_version()
    if major < _MIN_JAVA_MAJOR:
        raise HelperError(f"Java {_MIN_JAVA_MAJOR}+ required, found {major}")


# ---- dependencies ----------------------------------------------------------

def _parse_lock() -> list[tuple[str, str]]:
    """Parse deps.lock into ``[(sha256, url), ...]``."""
    deps: list[tuple[str, str]] = []
    for line in _DEPS_LOCK.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        sha, url = line.split()
        deps.append((sha, url))
    return deps


def _sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def ensure_deps() -> list[Path]:
    """Fetch any missing dependency jars and verify every SHA-256. Returns jar paths."""
    deps_dir = cache_dir() / "deps"
    deps_dir.mkdir(parents=True, exist_ok=True)
    jars: list[Path] = []
    for sha, url in _parse_lock():
        jar = deps_dir / url.rsplit("/", 1)[-1]
        if not jar.exists():
            tmp = jar.with_suffix(jar.suffix + ".tmp")
            with urllib.request.urlopen(url) as resp, tmp.open("wb") as out:
                shutil.copyfileobj(resp, out)
            tmp.replace(jar)
        actual = _sha256_file(jar)
        if actual != sha:
            jar.unlink(missing_ok=True)
            raise HelperError(
                f"sha256 mismatch for {jar.name}: expected {sha}, got {actual}")
        jars.append(jar)
    return jars


# ---- build -----------------------------------------------------------------

def _source_files() -> list[Path]:
    return sorted(_SRC_DIR.rglob("*.java"))


def _sources_fingerprint() -> str:
    """12-hex digest of the concatenated, path-sorted sources.

    Matches ``build.sh``: ``find src -name '*.java' | sort | xargs cat | sha256sum``.
    """
    h = hashlib.sha256()
    for path in _source_files():
        h.update(path.read_bytes())
    return h.hexdigest()[:12]


def _classpath(jars: Iterable[Path]) -> str:
    return os.pathsep.join(str(j) for j in jars)


def build_helper(force: bool = False) -> Path:
    """Build the helper jar if needed; return its path.

    Reuses an existing jar for the current sources unless ``force`` is set.
    """
    check_java()
    jars = ensure_deps()
    helper_dir = cache_dir() / "helper"
    helper_dir.mkdir(parents=True, exist_ok=True)
    jar_out = helper_dir / f"sgmo-helper-{_sources_fingerprint()}.jar"
    if jar_out.exists() and not force:
        return jar_out

    javac = _tool("javac")
    with tempfile.TemporaryDirectory() as work:
        classes = Path(work) / "classes"
        classes.mkdir()
        cmd = [javac, "--release", "17", "-cp", _classpath(jars),
               "-d", str(classes)] + [str(p) for p in _source_files()]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        if proc.returncode != 0:
            raise HelperError("javac failed", returncode=proc.returncode,
                              stdout=proc.stdout, stderr=proc.stderr)
        tmp_jar = Path(work) / "helper.jar"
        _pack_jar(classes, tmp_jar)
        shutil.move(str(tmp_jar), str(jar_out))
    return jar_out


def _pack_jar(classes_dir: Path, jar_out: Path) -> None:
    """Pack compiled classes into a runnable jar (``jar`` tool, else :mod:`zipfile`)."""
    manifest_main = "sgmo.helper.Main"
    jar_tool = None
    try:
        jar_tool = _tool("jar")
    except HelperError:
        jar_tool = None
    if jar_tool:
        proc = subprocess.run(
            [jar_tool, "--create", "--file", str(jar_out),
             "--main-class", manifest_main, "-C", str(classes_dir), "."],
            capture_output=True, text=True)
        if proc.returncode == 0:
            return
        # Fall through to the zipfile path on any jar-tool failure.
    _pack_jar_zipfile(classes_dir, jar_out, manifest_main)


def _pack_jar_zipfile(classes_dir: Path, jar_out: Path, main_class: str) -> None:
    import zipfile
    manifest = (
        "Manifest-Version: 1.0\r\n"
        f"Main-Class: {main_class}\r\n"
        "\r\n"
    )
    with zipfile.ZipFile(jar_out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("META-INF/MANIFEST.MF", manifest)
        for path in sorted(classes_dir.rglob("*")):
            if path.is_file():
                z.write(path, str(path.relative_to(classes_dir)))


# ---- run -------------------------------------------------------------------

def classpath() -> str:
    """The full classpath: the helper jar plus its dependency jars."""
    jar = build_helper()
    jars = ensure_deps()
    return _classpath([jar, *jars])


def run(args: Sequence[str], *, allowed_returncodes: Sequence[int] = (0,),
        input_text: str | None = None) -> Result:
    """Run a helper subcommand and capture its output.

    Raises :class:`HelperError` (carrying stderr) if the exit code is not in
    ``allowed_returncodes``. Note dex-patch returns 2 on a required-site mismatch and
    dex-compare returns 3 on a difference; pass those in ``allowed_returncodes`` to treat
    them as data rather than errors.
    """
    java = _tool("java")
    cp = classpath()
    cmd = [java, "-cp", cp, "sgmo.helper.Main", *args]
    proc = subprocess.run(cmd, capture_output=True, text=True, input=input_text)
    if proc.returncode not in allowed_returncodes:
        raise HelperError(
            f"helper command {args[0] if args else ''!r} failed (exit {proc.returncode})",
            returncode=proc.returncode, stdout=proc.stdout, stderr=proc.stderr)
    return Result(proc.returncode, proc.stdout, proc.stderr)


def selftest() -> Result:
    """Run the helper's built-in selftest (exit 0 on success)."""
    return run(["selftest"])


if __name__ == "__main__":  # pragma: no cover - manual use
    # `python -m sgmo_patcher.javahelper <args...>` runs the helper directly.
    result = run(sys.argv[1:], allowed_returncodes=(0, 1, 2, 3))
    sys.stdout.write(result.stdout)
    sys.stderr.write(result.stderr)
    sys.exit(result.returncode)
