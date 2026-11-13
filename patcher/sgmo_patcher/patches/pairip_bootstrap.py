"""pairip-bootstrap-stubs: stop PairIP's native startup VM program from running.

PairIP's ``StartupLauncher.launch()`` runs an encrypted startup program through its native VM on
first class-init (from ``Application.<clinit>`` and ``CoreComponentFactory.<clinit>``). On the b02
device run that program's integrity check crashed the process (SIGSEGV inside ``libpairipcore.so``)
because the APK is repackaged and re-signed. Stubbing ``launch()`` to return immediately stops it.

Off by default; enabled for the native-tamper experiments (b03+). Does not touch ``libpairipcore.so``
or the encrypted assets.
"""

from __future__ import annotations

from .base import Ctx, PatchError
from ..report import PatchRecord, SiteRecord

NAME = "pairip-bootstrap-stubs"
DESCRIPTION = "stub StartupLauncher.launch so PairIP's native startup program never runs"
DEFAULT_ON = False

_STUBS = [("Lcom/pairip/StartupLauncher;", "launch", "()V")]


def apply(ctx: Ctx, rec: PatchRecord) -> None:
    fps = ctx.sites.get("pairip_bootstrap", {})
    for cls, name, desc in _STUBS:
        key = f"{cls}->{name}{desc}"
        info = fps.get(key)
        if info is None and not ctx.force_unverified:
            raise PatchError(f"no verified-build fingerprint on record for {key} "
                             "(regenerate sites with tools/make_sites.py).")
        info = info or {}
        site = SiteRecord("dex-method", key, "pending", info.get("sha"), None,
                          f"{info.get('dex', 'classes2.dex')}: RETURN_VOID")
        rec.sites.append(site)
        ctx.add_dex_op(info.get("dex", "classes2.dex"), {
            "kind": "stub", "cls": cls, "name": name, "desc": desc,
            "expectRawSha256": info.get("sha"), "stub": "RETURN_VOID",
        }, site)
    rec.notes.append("startup VM program disabled; libpairipcore.so and the il2cpp DT_INIT path "
                     "are untouched (device log needed to confirm boot)")
