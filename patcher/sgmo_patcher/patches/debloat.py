"""debloat: stop the SDK auto-init ContentProviders from running at boot.

After PairIP's startup VM program is disabled (`pairip-bootstrap-stubs`), the string fields PairIP
would have populated stay null. The SDK code that reads them (Firebase/DataTransport, GMS ads,
Vungle, BidMachine, androidx startup) runs from ContentProviders during app start and crashes on the
null strings. None of it works offline, so this patch stubs each provider's ``onCreate()`` to return
true without initialising the SDK — the provider loads, but the SDK (and its null-string reads) does
not. The game's own code (IL2CPP) does not depend on these.

Off by default; enabled for the b04 boot experiments. Each present provider is fingerprinted; absent
ones are skipped.
"""

from __future__ import annotations

from .base import Ctx
from ..report import PatchRecord, SiteRecord

NAME = "debloat"
DESCRIPTION = "stub telemetry/ads ContentProvider onCreate() so those SDKs don't init at boot"
DEFAULT_ON = False

_PROVIDERS = [
    "Lcom/google/firebase/provider/FirebaseInitProvider;",
    "Lcom/google/android/gms/ads/MobileAdsInitProvider;",
    "Lcom/vungle/ads/VungleProvider;",
    "Lio/bidmachine/BidMachineInitProvider;",
    "Landroidx/startup/InitializationProvider;",
]


def apply(ctx: Ctx, rec: PatchRecord) -> None:
    fps = ctx.sites.get("debloat_providers", {})
    found = 0
    for cls in _PROVIDERS:
        key = f"{cls}->onCreate()Z"
        info = fps.get(key)
        if info is None:
            rec.sites.append(SiteRecord("dex-method", key, "skipped", None, None, "absent in this build"))
            continue
        found += 1
        site = SiteRecord("dex-method", key, "pending", info["sha"], None, f"{info['dex']}: RETURN_TRUE")
        rec.sites.append(site)
        ctx.add_dex_op(info["dex"], {
            "kind": "stub", "cls": cls, "name": "onCreate", "desc": "()Z",
            "expectRawSha256": info["sha"], "stub": "RETURN_TRUE",
        }, site)
    if not found:
        rec.status = "skipped"
        rec.notes.append("no known SDK providers found to debloat")
        return
    rec.notes.append("SDK auto-init providers neutralised so PairIP's null string fields are never "
                     "read at boot")
