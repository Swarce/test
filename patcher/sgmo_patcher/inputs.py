"""Load the player's own copy of the game: XAPK, .apks, a folder, or explicit APK files.

Accepted inputs (any mix, given as a list of paths):

- an ``.xapk`` (APKPure): a zip with ``manifest.json``, the base APK, split APKs, an icon;
- an ``.apks`` (bundletool / SAI): a zip with ``splits/base-master.apk``,
  ``splits/base-arm64_v8a.apk``, ... and possibly ``toc.pb``;
- a folder that contains the APK files (searched recursively, hidden folders skipped);
- one or more ``.apk`` files, e.g. ``base.apk`` and ``split_config.arm64_v8a.apk`` as pulled
  from a phone with ``adb pull``.

Files are recognised by content, not by extension: a zip with ``AndroidManifest.xml`` at the
top is an APK; a zip with ``.apk`` entries is a bundle. APKs inside a bundle are streamed out
to a temporary folder, which :meth:`ApkSet.cleanup` (or leaving the ``with`` block) removes.

Each APK's ``AndroidManifest.xml`` is decoded by ``sgmo_patcher.axml.manifest_info`` (or the
``manifest_reader`` passed to :func:`load`) to find the package, version and split name. The
result must contain exactly one base APK (no split name); every split must have the base's
package and versionCode. Non-APK files are ignored and listed in :attr:`ApkSet.ignored`.

Errors are :class:`InputError` with a message meant for players: what was found and what
was expected.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import tempfile
from dataclasses import dataclass, field
from typing import Callable, Optional, Sequence, Union

from .zipio import ZipError, ZipIn

__all__ = ["InputError", "ApkFile", "ApkSet", "Candidate", "collect", "load"]

ManifestReader = Callable[[bytes], dict]

EXPECTED = ("Expected: the game's .xapk or .apks file, a folder with its APK files, "
            "or base.apk together with its split APKs (for example split_config.arm64_v8a.apk).")


class InputError(Exception):
    """The given files are not a usable copy of the game. The message is for the player."""


@dataclass
class Candidate:
    """An APK file found while collecting inputs, before its manifest is read.

    ``path`` is a real file on disk (possibly extracted to the temporary folder); ``source``
    describes where it came from, e.g. ``game.xapk!com.autumn.skullgirls.apk``.
    """

    path: str
    source: str


@dataclass
class ApkFile:
    """One loaded APK: its zip, where it came from, and its manifest summary."""

    zip: ZipIn
    source: str
    path: str
    package: str
    version_code: int
    version_name: Optional[str]
    split: Optional[str]

    def describe(self) -> str:
        """Short, player-readable description, e.g. ``base.apk (com.x 266, base)``."""
        kind = f"split '{self.split}'" if self.split else "base"
        return f"{self.source} ({self.package} versionCode {self.version_code}, {kind})"


@dataclass
class ApkSet:
    """The player's copy: one base APK plus its splits.

    ``sources[0]`` describes the base APK, ``sources[1 + i]`` describes ``splits[i]``.
    Splits are sorted by split name. Use as a context manager, or call :meth:`cleanup`, to
    close the files and remove the temporary folder.
    """

    base: ZipIn
    splits: list[ZipIn]
    package: str
    version_code: int
    version_name: Optional[str]
    split_names: list[str]
    sources: list[str]
    files: list[ApkFile]
    ignored: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    temp_dir: Optional[str] = None

    def split(self, name: str) -> Optional[ZipIn]:
        """The split APK called ``name`` (e.g. 'config.arm64_v8a'), or None."""
        for split_name, z in zip(self.split_names, self.splits):
            if split_name == name:
                return z
        return None

    def cleanup(self) -> None:
        """Close every APK and remove the temporary folder (idempotent)."""
        for f in self.files:
            f.zip.close()
        _remove_dir(self.temp_dir)
        self.temp_dir = None

    def __enter__(self) -> "ApkSet":
        return self

    def __exit__(self, *exc) -> None:
        self.cleanup()


def _remove_dir(path: Optional[str]) -> None:
    if path and os.path.isdir(path):
        shutil.rmtree(path, ignore_errors=True)


def _default_manifest_reader(data: bytes) -> dict:
    """Decode a binary AndroidManifest.xml with sgmo_patcher.axml (imported on first use)."""
    import sgmo_patcher.axml  # lazy: keeps this module importable on its own

    return sgmo_patcher.axml.manifest_info(data)


_SAFE = re.compile(r"[^A-Za-z0-9._-]+")


def _safe_filename(index: int, entry_name: str) -> str:
    """A temp-folder file name for a bundled APK that cannot escape the folder."""
    base = _SAFE.sub("_", entry_name.rsplit("/", 1)[-1]).strip("._") or "split"
    if not base.lower().endswith(".apk"):
        base += ".apk"
    return f"{index:02d}-{base}"


class _Collector:
    """Turns the given paths into APK candidates, extracting bundles to a temp folder."""

    def __init__(self, tmp_parent: Optional[str]):
        self.tmp_parent = tmp_parent
        self.temp_dir: Optional[str] = None
        self.candidates: list[Candidate] = []
        self.ignored: list[str] = []
        self.warnings: list[str] = []
        self.xapk_claims: list[tuple[str, object, object]] = []  # (source, package, versionCode)
        self._seen: set[str] = set()
        self._extracted = 0

    def _workdir(self) -> str:
        if self.temp_dir is None:
            self.temp_dir = tempfile.mkdtemp(prefix="sgmo-inputs-", dir=self.tmp_parent)
        return self.temp_dir

    def _add(self, path: str, source: str) -> None:
        key = os.path.realpath(path)
        if key in self._seen:
            self.warnings.append(f"{source} was given more than once; using it once.")
            return
        self._seen.add(key)
        self.candidates.append(Candidate(path, source))

    def add_path(self, path: str) -> None:
        if not os.path.exists(path):
            raise InputError(f"Cannot find '{path}'. Check the path and try again. {EXPECTED}")
        if os.path.isdir(path):
            self._add_dir(path)
        else:
            self._add_file(path, path)

    def _add_dir(self, folder: str) -> None:
        found = 0
        for root, dirs, files in os.walk(folder):
            dirs[:] = sorted(d for d in dirs if not d.startswith("."))
            for name in sorted(files):
                full = os.path.join(root, name)
                if name.lower().endswith(".apk"):
                    self._add(full, full)
                    found += 1
                else:
                    self.ignored.append(full)
        if not found:
            bundles = [p for p in self.ignored
                       if p.startswith(folder) and p.lower().endswith((".xapk", ".apks"))]
            hint = (f" It does contain {os.path.basename(bundles[0])}: pass that file directly."
                    if bundles else "")
            raise InputError(f"The folder '{folder}' contains no .apk files.{hint} {EXPECTED}")

    def _add_file(self, path: str, source: str) -> None:
        try:
            z = ZipIn(path)
        except ZipError as exc:
            raise InputError(
                f"'{source}' is not an APK, XAPK or APKS file (it is not a valid zip: {exc}). "
                f"{EXPECTED}") from exc
        except OSError as exc:
            raise InputError(f"Cannot read '{source}': {exc.strerror or exc}.") from exc
        with z:
            if "AndroidManifest.xml" in z:
                self._add(path, source)
                return
            inner = [e for e in z.entries
                     if not e.is_dir and e.name.lower().endswith(".apk")]
            if not inner:
                listing = ", ".join(z.names()[:8]) or "nothing"
                raise InputError(
                    f"'{source}' is a zip file but neither an APK (no AndroidManifest.xml) nor "
                    f"an XAPK/APKS bundle (no .apk files inside; it contains {listing}). "
                    f"{EXPECTED}")
            self._check_xapk_manifest(z, source)
            inner_names = {e.name for e in inner}
            for e in z.entries:
                if e.name not in inner_names and not e.is_dir:
                    self.ignored.append(f"{source}!{e.name}")
            for e in inner:
                self._extract(z, e, source)

    def _extract(self, z: ZipIn, entry, container: str) -> None:
        self._extracted += 1
        dest = os.path.join(self._workdir(), _safe_filename(self._extracted, entry.name))
        source = f"{container}!{entry.name}"
        try:
            with open(dest, "wb") as out:
                for chunk in z.iter_data(entry):
                    out.write(chunk)
        except ZipError as exc:
            raise InputError(f"Cannot extract {source}: {exc}. The file may be damaged; "
                             "download it again.") from exc
        except OSError as exc:
            raise InputError(f"Cannot extract {source} to the temporary folder "
                             f"{self.temp_dir}: {exc.strerror or exc}. Is the disk full?") from exc
        self._add(dest, source)

    def _check_xapk_manifest(self, z: ZipIn, source: str) -> None:
        """Remember the XAPK's manifest.json claims, to cross-check after loading."""
        if "manifest.json" not in z:
            return
        try:
            meta = json.loads(z.read("manifest.json").decode("utf-8"))
        except (ZipError, ValueError, UnicodeDecodeError):
            self.warnings.append(f"{source}!manifest.json is not valid JSON; ignored it.")
            return
        if isinstance(meta, dict):
            self.xapk_claims.append((source, meta.get("package_name"), meta.get("version_code")))


def _collect(paths, tmp_dir: Optional[str]) -> _Collector:
    """Run a :class:`_Collector` over ``paths``; removes its temp folder if it fails."""
    if isinstance(paths, (str, os.PathLike)):
        paths = [paths]
    paths = [os.fspath(p) for p in paths]
    if not paths:
        raise InputError(f"No input files were given. {EXPECTED}")
    c = _Collector(tmp_dir)
    try:
        for p in paths:
            c.add_path(p)
        if not c.candidates:
            raise InputError(f"No APK files were found in: {', '.join(paths)}. {EXPECTED}")
    except BaseException:
        _remove_dir(c.temp_dir)
        raise
    return c


def collect(paths: Union[str, Sequence[str]], tmp_dir: Optional[str] = None
            ) -> tuple[list[Candidate], list[str], list[str], Optional[str]]:
    """Find every APK in ``paths`` without reading manifests.

    Returns ``(candidates, ignored, warnings, temp_dir)``. APKs inside bundles are extracted
    to ``temp_dir`` (created under ``tmp_dir``, default: the system temp folder; None when
    nothing needed extracting); the caller owns it and must remove it. On error the temp
    folder is removed before raising.
    """
    c = _collect(paths, tmp_dir)
    return c.candidates, c.ignored, c.warnings, c.temp_dir


def _read_apk(cand: Candidate, reader: ManifestReader) -> ApkFile:
    try:
        z = ZipIn(cand.path)
    except ZipError as exc:
        raise InputError(f"'{cand.source}' is not a valid APK (not a valid zip: {exc}).") from exc
    try:
        if "AndroidManifest.xml" not in z:
            raise InputError(f"'{cand.source}' does not look like an APK: it has no "
                             f"AndroidManifest.xml. {EXPECTED}")
        try:
            data = z.read("AndroidManifest.xml")
        except ZipError as exc:
            raise InputError(f"The AndroidManifest.xml of '{cand.source}' is damaged ({exc}). "
                             "Download the game again.") from exc
        try:
            info = reader(data)
        except InputError:
            raise
        except Exception as exc:
            raise InputError(f"Cannot read the AndroidManifest.xml of '{cand.source}': {exc}."
                             ) from exc
        if not isinstance(info, dict) or not info.get("package"):
            raise InputError(f"The AndroidManifest.xml of '{cand.source}' has no package name.")
        try:
            version_code = int(info.get("versionCode"))
        except (TypeError, ValueError):
            raise InputError(f"The AndroidManifest.xml of '{cand.source}' has no valid "
                             f"versionCode (found {info.get('versionCode')!r}).") from None
        split = info.get("split") or None
        return ApkFile(zip=z, source=cand.source, path=cand.path, package=str(info["package"]),
                       version_code=version_code, version_name=info.get("versionName"),
                       split=str(split) if split else None)
    except BaseException:
        z.close()
        raise


def load(paths: Union[str, Sequence[str]], manifest_reader: Optional[ManifestReader] = None,
         tmp_dir: Optional[str] = None) -> ApkSet:
    """Load the player's copy of the game from ``paths``; returns an :class:`ApkSet`.

    ``manifest_reader(data) -> {package, versionCode, versionName, split}`` decodes a binary
    AndroidManifest.xml; the default is ``sgmo_patcher.axml.manifest_info``. ``tmp_dir`` is
    where the temporary folder for bundled APKs is created (default: the system temp folder).
    Raises :class:`InputError` with a player-readable message; nothing is left open or on
    disk when it does.
    """
    reader = manifest_reader or _default_manifest_reader
    c = _collect(paths, tmp_dir)
    candidates, ignored, warnings, temp_dir = c.candidates, c.ignored, c.warnings, c.temp_dir
    files: list[ApkFile] = []
    try:
        for cand in candidates:
            files.append(_read_apk(cand, reader))
        base, splits = _check_set(files)
        for source, pkg, vc in c.xapk_claims:
            if (pkg and pkg != base.package) or (vc and str(vc) != str(base.version_code)):
                warnings.append(
                    f"{source}: manifest.json says {pkg} versionCode {vc}, but the APKs are "
                    f"{base.package} versionCode {base.version_code}; using the APKs.")
        ordered = [base] + splits
        return ApkSet(
            base=base.zip, splits=[s.zip for s in splits], package=base.package,
            version_code=base.version_code, version_name=base.version_name,
            split_names=[s.split for s in splits], sources=[f.source for f in ordered],
            files=ordered, ignored=ignored, warnings=warnings, temp_dir=temp_dir)
    except BaseException:
        for f in files:
            f.zip.close()
        _remove_dir(temp_dir)
        raise


def _check_set(files: list[ApkFile]) -> tuple[ApkFile, list[ApkFile]]:
    """Exactly one base; every split matches its package and versionCode; no repeats."""
    bases = [f for f in files if f.split is None]
    splits = [f for f in files if f.split is not None]
    found = "; ".join(f.describe() for f in files)
    if not bases:
        raise InputError(
            f"No base APK was found. All {len(files)} APK file(s) are split APKs: {found}. "
            f"The base APK (base.apk, or the one named after the game) is missing. {EXPECTED}")
    if len(bases) > 1:
        raise InputError(
            f"Found {len(bases)} base APKs, but exactly one is needed: "
            f"{'; '.join(b.describe() for b in bases)}. Give the files of one copy of the game "
            "only.")
    base = bases[0]
    for s in splits:
        if s.package != base.package:
            raise InputError(
                f"{s.describe()} belongs to a different app than the base APK "
                f"{base.describe()}. All APK files must come from the same game.")
        if s.version_code != base.version_code:
            raise InputError(
                f"{s.describe()} is from a different version than the base APK "
                f"{base.describe()}. All APK files must come from the same version of the "
                "game; download or pull them again together.")
    seen: dict[str, ApkFile] = {}
    for s in splits:
        if s.split in seen:
            raise InputError(
                f"The split '{s.split}' was given twice: {seen[s.split].source} and {s.source}. "
                "Give each split APK once.")
        seen[s.split] = s
    splits.sort(key=lambda f: f.split)
    return base, splits
