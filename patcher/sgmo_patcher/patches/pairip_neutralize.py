"""pairip-neutralize: stub the two Java PairIP gates that reject a re-signed APK.

Minimal and Java-only (see docs/research/07-integrity.md): stub only the two methods that reject a
re-signed / unlicensed build — ``SignatureCheck.verifyIntegrity`` and
``LicenseClient.checkLicense`` — to return-void. The native ``libpairipcore.so``, the encrypted VM
assets and the bootstrap are left intact. Each site is pinned by its raw-insns fingerprint; the
orchestrator applies all of a dex's edits in one pass and proves nothing else changed.
"""

from __future__ import annotations

from .base import Ctx, PatchError
from ..report import PatchRecord, SiteRecord

NAME = "pairip-neutralize"
DESCRIPTION = "stub PairIP's Java signature and license checks (keep the native VM intact)"
DEFAULT_ON = True

_GATES = [
    ("Lcom/pairip/SignatureCheck;", "verifyIntegrity", "(Landroid/content/Context;)V"),
    ("Lcom/pairip/licensecheck/LicenseClient;", "checkLicense", "(Landroid/content/Context;)V"),
]


def apply(ctx: Ctx, rec: PatchRecord) -> None:
    gate_fps = ctx.sites.get("pairip_gates", {})
    for cls, name, desc in _GATES:
        key = f"{cls}->{name}{desc}"
        info = gate_fps.get(key)
        if info is None and not ctx.force_unverified:
            raise PatchError(f"no verified-build fingerprint on record for {key}.")
        info = info or {}
        site = SiteRecord("dex-method", key, "pending", info.get("sha"), None,
                          f"{info.get('dex', 'classes2.dex')}: RETURN_VOID")
        rec.sites.append(site)
        ctx.add_dex_op(info.get("dex", "classes2.dex"), {
            "kind": "stub", "cls": cls, "name": name, "desc": desc,
            "expectRawSha256": info.get("sha"), "stub": "RETURN_VOID",
        }, site)
    rec.notes.append("native libpairipcore.so, encrypted VM assets and the bootstrap left intact "
                     "(see docs/research/07-integrity.md)")
