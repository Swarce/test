"""Signing-key management and APK signing/verification.

The patcher signs every build with one local key, so a rebuilt APK installs over the old
one and keeps the game's save data. The key is created with the JDK's ``keytool`` and never
enters the repo.

* :func:`keygen` -- create the PKCS12 keystore (RSA 4096, 50-year validity) with a random
  password, refusing to overwrite an existing keystore.
* :func:`keystore_cert_sha256` -- the SHA-256 of the keystore certificate (DER).
* :func:`sign` / :func:`verify` -- wrap the Java helper's apksig commands.
* :func:`apksigner_verify` -- an independent check with Google's ``apksigner`` when
  ``SGMO_BUILD_TOOLS`` points at an Android build-tools folder.

Standard library only.
"""

from __future__ import annotations

import hashlib
import os
import secrets
import stat
import subprocess
import tempfile
from pathlib import Path

from . import javahelper

#: Keystore alias and certificate subject.
ALIAS = "sgm-offline"
_DNAME = "CN=sgm-offline local build key"
_VALIDITY_DAYS = 18250  # ~50 years


def _config_dir() -> Path:
    return Path.home() / ".config" / "sgm-offline"


def keystore_path() -> Path:
    """The keystore path ($SGMO_KEYSTORE, else ~/.config/sgm-offline/sgm-offline-signing.p12)."""
    env = os.environ.get("SGMO_KEYSTORE")
    return Path(env) if env else _config_dir() / "sgm-offline-signing.p12"


def pass_file_path() -> Path:
    """The password file path ($SGMO_KEYSTORE_PASS_FILE, else alongside the keystore)."""
    env = os.environ.get("SGMO_KEYSTORE_PASS_FILE")
    return Path(env) if env else _config_dir() / "sgm-offline-signing.pass"


def _keytool() -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidate = Path(java_home) / "bin" / "keytool"
        if candidate.exists():
            return str(candidate)
    import shutil
    found = shutil.which("keytool")
    if not found:
        raise javahelper.HelperError("cannot find 'keytool'; set JAVA_HOME or put a JDK on PATH")
    return found


def keygen(path: Path | None = None, pass_file: Path | None = None) -> Path:
    """Create the signing keystore. Refuses to overwrite an existing keystore.

    Returns the keystore path. A random password is written to ``pass_file`` with mode 0600.
    """
    ks = Path(path) if path else keystore_path()
    pf = Path(pass_file) if pass_file else pass_file_path()
    if ks.exists():
        raise FileExistsError(f"refusing to overwrite existing keystore: {ks}")

    ks.parent.mkdir(parents=True, exist_ok=True)
    pf.parent.mkdir(parents=True, exist_ok=True)

    password = secrets.token_urlsafe(32)
    # Write the password file first, locked down, so the keystore is never passworded
    # without a stored password beside it.
    pf.write_text(password)
    os.chmod(pf, stat.S_IRUSR | stat.S_IWUSR)  # 0600

    cmd = [
        _keytool(), "-genkeypair",
        "-keystore", str(ks),
        "-storetype", "PKCS12",
        "-storepass", password,
        "-keypass", password,
        "-alias", ALIAS,
        "-keyalg", "RSA",
        "-keysize", "4096",
        "-sigalg", "SHA256withRSA",
        "-validity", str(_VALIDITY_DAYS),
        "-dname", _DNAME,
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    if proc.returncode != 0:
        # Do not leave a half-made keystore / stray password around.
        ks.unlink(missing_ok=True)
        pf.unlink(missing_ok=True)
        raise javahelper.HelperError(
            "keytool failed", returncode=proc.returncode,
            stdout=proc.stdout, stderr=proc.stderr)
    return ks


def ensure_keystore() -> Path:
    """Return the keystore path, creating it if it does not exist."""
    ks = keystore_path()
    if not ks.exists():
        keygen(ks, pass_file_path())
    return ks


def keystore_cert_sha256(path: Path | None = None, pass_file: Path | None = None) -> str:
    """SHA-256 (lowercase hex) of the keystore certificate's DER encoding."""
    ks = Path(path) if path else keystore_path()
    pf = Path(pass_file) if pass_file else pass_file_path()
    password = _read_password(pf)
    with tempfile.TemporaryDirectory() as d:
        der = Path(d) / "cert.der"
        cmd = [
            _keytool(), "-exportcert",
            "-keystore", str(ks),
            "-storetype", "PKCS12",
            "-storepass", password,
            "-alias", ALIAS,
            "-file", str(der),
        ]
        proc = subprocess.run(cmd, capture_output=True, text=True)
        if proc.returncode != 0:
            raise javahelper.HelperError(
                "keytool -exportcert failed", returncode=proc.returncode,
                stdout=proc.stdout, stderr=proc.stderr)
        return hashlib.sha256(der.read_bytes()).hexdigest()


def sign(unsigned_path: Path | str, out_path: Path | str, *,
         keystore: Path | None = None, pass_file: Path | None = None,
         alias: str = ALIAS, min_sdk: int = 24) -> dict:
    """Sign ``unsigned_path`` to ``out_path`` with v1+v2+v3. Returns the helper's JSON."""
    ks = Path(keystore) if keystore else ensure_keystore()
    pf = Path(pass_file) if pass_file else pass_file_path()
    result = javahelper.run([
        "sign",
        "--in", str(unsigned_path),
        "--out", str(out_path),
        "--ks", str(ks),
        "--ks-pass-file", str(pf),
        "--alias", alias,
        "--min-sdk", str(min_sdk),
    ])
    return result.json()


def verify(apk_path: Path | str, *, min_sdk: int = 24) -> dict:
    """Verify an APK's signatures via the helper (apksig). Returns the helper's JSON."""
    result = javahelper.run(["verify", str(apk_path), "--min-sdk", str(min_sdk)])
    return result.json()


def apksigner_verify(apk_path: Path | str, *, min_sdk: int = 24) -> dict | None:
    """Independently verify with Google's ``apksigner`` from ``$SGMO_BUILD_TOOLS``.

    Returns ``None`` when ``SGMO_BUILD_TOOLS`` is not set. Otherwise returns
    ``{"verified": bool, "output": str}``.
    """
    build_tools = os.environ.get("SGMO_BUILD_TOOLS")
    if not build_tools:
        return None
    apksigner = Path(build_tools) / "apksigner"
    if not apksigner.exists():
        raise javahelper.HelperError(f"apksigner not found in {build_tools}")
    cmd = [str(apksigner), "verify", "--min-sdk-version", str(min_sdk),
           "--verbose", str(apk_path)]
    proc = subprocess.run(cmd, capture_output=True, text=True)
    return {
        "verified": proc.returncode == 0,
        "output": proc.stdout + proc.stderr,
    }


def _read_password(pf: Path) -> str:
    text = pf.read_text()
    return text.rstrip("\r\n")
