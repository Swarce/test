"""Shared helpers for patches: the per-run context and the fingerprint rule."""

from __future__ import annotations

import hashlib
from typing import Optional

from ..zipio import RawCopy, ZipPlan
from ..report import PatchRecord, SiteRecord


class PatchError(Exception):
    """A required patch site did not match the verified build, or a patch cannot proceed."""


class Ctx:
    """Everything a patch needs for one run."""

    def __init__(self, apk, plan: ZipPlan, sites: dict, config: dict):
        self.apk = apk              # ApkSet
        self.plan = plan            # the mutable output entry map
        self.sites = sites          # the verified-build fingerprints (sites/<versionCode>.json)
        self.config = config        # {"port": int, "force_unverified": bool, ...}
        # Collected dex-method edits (stubs/injects), grouped by dex and applied in one pass each
        # by the orchestrator, so every edit's fingerprint is checked against the original dex.
        self.dex_ops: dict[str, list] = {}

    def add_dex_op(self, dex_name: str, op: dict, site) -> None:
        """Declare a dex-method edit; the orchestrator applies all of a dex's edits together.

        ``op`` keys: kind ("stub"|"inject"), cls, name, desc, expectRawSha256, and either stub
        (+superCls) or invoke; ``required`` defaults true. ``site`` is the pending SiteRecord the
        flush fills in.
        """
        op.setdefault("required", True)
        self.dex_ops.setdefault(dex_name, []).append((op, site))

    # -- reading the current bytes of an output entry (base copy or already replaced) ----------
    def entry_bytes(self, name: str) -> bytes:
        src = self.plan.get_source(name)
        if isinstance(src, RawCopy):
            return src.zipin.read(src.entry)
        return src.data

    def has(self, name: str) -> bool:
        return self.plan.has(name)

    def replace(self, name: str, data: bytes, method: Optional[str] = None) -> None:
        self.plan.replace_bytes(name, data, method)

    @property
    def force_unverified(self) -> bool:
        return bool(self.config.get("force_unverified"))


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def check_file(ctx: Ctx, name: str, expected_sha256: Optional[str],
               rec: PatchRecord) -> tuple[bytes, bool]:
    """Read an output entry and check its whole-file fingerprint against the verified build.

    Returns (bytes, matched). On a mismatch with a known expected hash, records a site and
    (unless --force-unverified) raises PatchError.
    """
    data = ctx.entry_bytes(name)
    actual = sha256_hex(data)
    if expected_sha256 is None:
        rec.sites.append(SiteRecord("file", name, "applied", None, actual,
                                    "no verified-build hash on record"))
        return data, False
    if actual.lower() != expected_sha256.lower():
        rec.sites.append(SiteRecord("file", name, "refused", expected_sha256, actual,
                                    "file differs from the verified build"))
        if not ctx.force_unverified:
            raise PatchError(
                f"{name} does not match the verified 8.12.0 build "
                f"(expected {expected_sha256[:16]}…, got {actual[:16]}…). "
                "This is not the expected version, or the copy is modified. "
                "Use --force-unverified to patch anyway at your own risk.")
        rec.notes.append(f"{name}: continuing despite a fingerprint mismatch (--force-unverified)")
        return data, False
    return data, True
