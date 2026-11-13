"""pairip-bootstrap-stubs: stop PairIP's native startup VM program from running.

PairIP's `StartupLauncher.launch()` runs an encrypted "startup program" through its native VM
(`libpairipcore.so`) on first class-init, from both `Application.<clinit>` and
`androidx.core.app.CoreComponentFactory.<clinit>`. On the b02 device run that program's integrity
check crashed the process (SIGSEGV inside `libpairipcore.so`) because the APK is repackaged and
re-signed — before any of our code ran.

This patch stubs `launch()` to return immediately, so the startup program never runs. It does **not**
touch `libpairipcore.so` (still a `DT_NEEDED` of the game's own libraries) or the encrypted assets.
It is off by default and enabled for the native-tamper experiments (b03): there is a second native
entry point (the `DT_INIT` of `libil2cpp.so`) that this does not cover, so a device log is still how
we learn whether it is enough.
"""

from __future__ import annotations

from collections import defaultdict

from .base import Ctx, PatchError
from ..report import PatchRecord, SiteRecord
from .. import dex

NAME = "pairip-bootstrap-stubs"
DESCRIPTION = "stub StartupLauncher.launch so PairIP's native startup program never runs"
DEFAULT_ON = False

# The bootstrap methods to stub. launch() returns void, so RETURN_VOID is a faithful no-op.
_STUBS = [
    {"cls": "Lcom/pairip/StartupLauncher;", "name": "launch", "desc": "()V", "stub": "RETURN_VOID"},
]


def _key(g: dict) -> str:
    return f"{g['cls']}->{g['name']}{g['desc']}"


def apply(ctx: Ctx, rec: PatchRecord) -> None:
    fps = ctx.sites.get("pairip_bootstrap", {})
    by_dex: dict[str, list[dict]] = defaultdict(list)
    for g in _STUBS:
        info = fps.get(_key(g))
        if info is None and not ctx.force_unverified:
            raise PatchError(f"no verified-build fingerprint on record for {_key(g)} "
                             "(regenerate sites with tools/make_sites.py).")
        info = info or {}
        by_dex[info.get("dex", "classes2.dex")].append({**g, "sha": info.get("sha"), "_key": _key(g)})

    for dex_name, stubs in by_dex.items():
        if not ctx.has(dex_name):
            raise PatchError(f"{dex_name} is not in the APK")
        data = ctx.entry_bytes(dex_name)
        sites = [{"cls": g["cls"], "name": g["name"], "desc": g["desc"],
                  "expectRawSha256": g["sha"], "stub": g["stub"], "required": True} for g in stubs]
        out, results = dex.dex_patch(data, sites)
        by_key = {f"{r['cls']}->{r['name']}{r['desc']}": r for r in results}
        if out is None:
            for g in stubs:
                r = by_key.get(g["_key"], {})
                rec.sites.append(SiteRecord("dex-method", g["_key"], "refused", g["sha"],
                                            r.get("actualRawSha256"), f"{dex_name}: {r.get('status')}"))
            raise PatchError(f"a bootstrap method in {dex_name} did not match the verified build.")
        ok, diffs = dex.dex_compare(
            data, out, [{"cls": g["cls"], "name": g["name"], "desc": g["desc"]} for g in stubs])
        if not ok:
            raise PatchError(f"{dex_name}: the rewrite changed more than the stubbed methods: "
                             + "; ".join(diffs[:5]))
        ctx.replace(dex_name, out)
        for g in stubs:
            r = by_key.get(g["_key"], {})
            rec.sites.append(SiteRecord("dex-method", g["_key"], "applied", g["sha"],
                                        r.get("actualRawSha256"), f"{dex_name}: {g['stub']}"))
    rec.notes.append("startup VM program disabled; libpairipcore.so and the il2cpp DT_INIT path "
                     "are untouched (device log needed to confirm boot)")
