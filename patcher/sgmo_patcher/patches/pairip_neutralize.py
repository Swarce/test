"""pairip-neutralize: stub the two Java PairIP gates that reject a re-signed APK.

Minimal and Java-only (see docs/research/07-integrity.md): PairIP's native component
(``libpairipcore.so``) cannot be removed, so b01 touches neither the native libraries nor the
encrypted VM assets nor the bootstrap. It stubs only the two methods that would otherwise reject a
re-signed / unlicensed build:

- ``com.pairip.SignatureCheck.verifyIntegrity(Context)`` — throws ``SignatureTamperedException`` when
  the APK signature does not match; stubbed to return-void.
- ``com.pairip.licensecheck.LicenseClient.checkLicense(Context)`` — the Play license gate (called from
  both ``Application.attachBaseContext`` and ``LicenseContentProvider.onCreate``); stubbed to
  return-void at the source method, so both callers are covered.

Each site is located by (class, method, descriptor) and its pre-image is verified by the raw-insns
fingerprint before patching. The dex is then rebuilt and proven equal to the input except for the
stubbed methods. The optional bootstrap/guarded-method stubs are a separate concern, off by default.
"""

from __future__ import annotations

from collections import defaultdict

from .base import Ctx, PatchError
from ..report import PatchRecord, SiteRecord
from .. import dex

NAME = "pairip-neutralize"
DESCRIPTION = "stub PairIP's Java signature and license checks (keep the native VM intact)"
DEFAULT_ON = True

# The two gates, as recovered from the verified 8.12.0 build. The expected fingerprint and the dex
# each lives in come from sites/<versionCode>.json ("pairip_gates": {key: {sha, dex}}).
_GATES = [
    {"cls": "Lcom/pairip/SignatureCheck;", "name": "verifyIntegrity",
     "desc": "(Landroid/content/Context;)V", "stub": "RETURN_VOID"},
    {"cls": "Lcom/pairip/licensecheck/LicenseClient;", "name": "checkLicense",
     "desc": "(Landroid/content/Context;)V", "stub": "RETURN_VOID"},
]


def _key(g: dict) -> str:
    return f"{g['cls']}->{g['name']}{g['desc']}"


def apply(ctx: Ctx, rec: PatchRecord) -> None:
    gate_fps = ctx.sites.get("pairip_gates", {})
    by_dex: dict[str, list[dict]] = defaultdict(list)
    for g in _GATES:
        info = gate_fps.get(_key(g))
        if info is None and not ctx.force_unverified:
            raise PatchError(f"no verified-build fingerprint on record for {_key(g)}.")
        info = info or {}
        dex_name = info.get("dex", "classes2.dex")
        by_dex[dex_name].append({**g, "expectRawSha256": info.get("sha"), "_key": _key(g)})

    for dex_name, gates in by_dex.items():
        if not ctx.has(dex_name):
            raise PatchError(f"{dex_name} is not in the APK; cannot stub "
                             + ", ".join(g["_key"] for g in gates))
        data = ctx.entry_bytes(dex_name)
        sites = [{"cls": g["cls"], "name": g["name"], "desc": g["desc"],
                  "expectRawSha256": g["expectRawSha256"], "stub": g["stub"], "required": True}
                 for g in gates]
        out, results = dex.dex_patch(data, sites)
        by_key = {f"{r['cls']}->{r['name']}{r['desc']}": r for r in results}
        if out is None:
            for g in gates:
                r = by_key.get(g["_key"], {})
                rec.sites.append(SiteRecord("dex-method", g["_key"], "refused",
                                            g["expectRawSha256"], r.get("actualRawSha256"),
                                            f"{dex_name}: {r.get('status', 'mismatch')}"))
            raise PatchError(
                f"a PairIP gate in {dex_name} did not match the verified build "
                f"({', '.join(g['_key'] for g in gates)}). This is not the expected 8.12.0 build.")
        ok, diffs = dex.dex_compare(
            data, out, [{"cls": g["cls"], "name": g["name"], "desc": g["desc"]} for g in gates])
        if not ok:
            raise PatchError(f"{dex_name}: the rewrite changed more than the stubbed methods: "
                             + "; ".join(diffs[:5]))
        ctx.replace(dex_name, out)
        for g in gates:
            r = by_key.get(g["_key"], {})
            rec.sites.append(SiteRecord("dex-method", g["_key"], "applied",
                                        g["expectRawSha256"], r.get("actualRawSha256"),
                                        f"{dex_name}: {g['stub']}"))

    rec.notes.append("native libpairipcore.so, encrypted VM assets and the bootstrap left intact "
                     "(see docs/research/07-integrity.md)")
