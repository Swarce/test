"""The patch orchestrator: inputs -> patched, re-signed APK + report.

Loads the player's copy, checks it is the version we have fingerprints for, builds the output entry
map (every base entry raw-copied), runs the selected patches, strips the old signatures, writes an
aligned zip, signs it (v1+v2+v3) and verifies the result.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from . import axml, inputs, sign, zipio
from .patches import ALL as ALL_PATCHES, BY_NAME
from .patches.base import Ctx, PatchError, sha256_hex
from .report import Report, PatchRecord, SiteRecord
from .sites import load_sites, SitesError

__all__ = ["patch", "PatchError", "PatchOptions"]

# signature-related entries that must be dropped before re-signing
_SIG_RE = re.compile(r"^META-INF/.*\.(SF|RSA|EC|DSA)$|^META-INF/MANIFEST\.MF$", re.IGNORECASE)
_STAMP = "stamp-cert-sha256"


@dataclass
class PatchOptions:
    port: int = 47820
    enable: list[str] = field(default_factory=list)       # patches to force on
    disable: list[str] = field(default_factory=list)      # patches to force off
    force_unverified: bool = False
    keystore: Optional[Path] = None
    pass_file: Optional[Path] = None
    min_sdk: int = 24


def _selected(opts: PatchOptions):
    out = []
    for m in ALL_PATCHES:
        on = m.DEFAULT_ON
        if m.NAME in opts.enable:
            on = True
        if m.NAME in opts.disable:
            on = False
        out.append((m, on))
    return out


def patch(input_paths, out_apk, opts: Optional[PatchOptions] = None, *,
          tool_version: str = "0.1.0-b01") -> Report:
    """Produce ``out_apk`` from ``input_paths``; returns the :class:`Report` (also written to disk)."""
    opts = opts or PatchOptions()
    out_apk = Path(out_apk)
    out_apk.parent.mkdir(parents=True, exist_ok=True)
    report = Report(tool_version=tool_version, port=opts.port, output=str(out_apk))

    with inputs.load(input_paths, manifest_reader=axml.manifest_info) as apk:
        report.package = apk.package
        report.version_code = apk.version_code
        report.version_name = apk.version_name or ""
        report.inputs = list(apk.sources)

        sites = load_sites(apk.version_code)
        if sites is None and not opts.force_unverified:
            raise PatchError(
                f"no verified-build fingerprints for versionCode {apk.version_code} "
                f"({apk.version_name}). This tool was made for 8.12.0 (versionCode 266). "
                "Use --force-unverified to try anyway.")
        sites = sites or {}

        # 1. output entry map: raw-copy every base entry except the old signatures and stamp.
        plan = zipio.ZipPlan()
        dropped = 0
        for e in apk.base.entries:
            if e.is_dir:
                continue
            if _SIG_RE.match(e.name) or e.name == _STAMP:
                dropped += 1
                continue
            plan.add_raw(apk.base, e)
        strip = report.patch("strip-signatures",
                             "drop the old v1 signature files and the Play source stamp")
        strip.sites.append(SiteRecord("file", "META-INF/*, stamp-cert-sha256", "applied",
                                      None, str(dropped), f"{dropped} entries removed before re-signing"))

        # 2. run the selected patches.
        ctx = Ctx(apk, plan, sites, {"port": opts.port, "force_unverified": opts.force_unverified})
        for module, on in _selected(opts):
            rec = PatchRecord(name=module.NAME, description=module.DESCRIPTION,
                              status="applied" if on else "off")
            report.patches.append(rec)
            if not on:
                continue
            module.apply(ctx, rec)

        # 3. write the aligned unsigned zip, then sign it.
        deliver = out_apk.parent
        unsigned = deliver / (out_apk.stem + ".unsigned.apk")
        summary = zipio.write(plan, unsigned)
        report.verification["entries"] = summary.get("entries") if isinstance(summary, dict) else None

        ks = opts.keystore or sign.ensure_keystore()
        sresult = sign.sign(unsigned, out_apk, keystore=ks, pass_file=opts.pass_file,
                            min_sdk=opts.min_sdk)
        unsigned.unlink(missing_ok=True)

        # 4. verify.
        v = sign.verify(out_apk, min_sdk=opts.min_sdk)
        align = zipio.verify_alignment(out_apk)
        cert = sresult.get("certSha256")
        report.verification.update({
            "signed_schemes": sresult.get("schemes"),
            "verified": v.get("verified"),
            "v1": v.get("v1"), "v2": v.get("v2"), "v3": v.get("v3"),
            "cert_sha256": cert,
            "alignment_violations": align,
        })
        report.ok = bool(v.get("verified")) and not align
        report.write(out_apk)
        return report
