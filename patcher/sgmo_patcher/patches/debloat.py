"""debloat: stop the telemetry/ads SDKs from initialising at boot.

After PairIP's startup VM program is disabled (`pairip-bootstrap-stubs`), the string fields PairIP
would have populated stay null. SDK code that reads them (Firebase/DataTransport, GMS ads, AppLovin,
Facebook, Adjust, Vungle, BidMachine) runs from ContentProviders during app start and crashes on the
null strings — in `attachInfo` (before `onCreate` even runs, e.g. GMS ads' "Missing application ID").
None of it works offline.

So this patch **removes those `<provider>` elements from the manifest** outright, so Android never
instantiates them (no `attachInfo`, no `onCreate`), plus stubs `androidx.startup.InitializationProvider.onCreate`
(kept in the manifest, but its initialisers skipped). The manifest edit shrinks the file; the
in-place split edits from `merge-splits` are unaffected. The game's own code (IL2CPP) does not depend
on any of this.

Off by default; enabled for the b05 boot experiments.
"""

from __future__ import annotations

from .base import Ctx, PatchError
from ..report import PatchRecord, SiteRecord
from .. import axml

NAME = "debloat"
DESCRIPTION = "remove telemetry/ads ContentProviders from the manifest; skip androidx-startup init"
DEFAULT_ON = False

# SDK ContentProviders removed from the manifest entirely.
_REMOVE_PROVIDERS = {
    "com.google.android.gms.ads.MobileAdsInitProvider",
    "com.google.firebase.provider.FirebaseInitProvider",
    "com.applovin.sdk.AppLovinInitProvider",
    "com.facebook.internal.FacebookInitProvider",
    "com.facebook.ads.AudienceNetworkContentProvider",
    "com.adjust.sdk.SystemLifecycleContentProvider",
    "io.bidmachine.BidMachineInitProvider",
    "com.vungle.ads.VungleProvider",
}
# Kept in the manifest but its initialisers skipped (androidx startup can register SDK initialisers).
_STUB_ONCREATE = ["Landroidx/startup/InitializationProvider;"]

_MAN = "AndroidManifest.xml"


def apply(ctx: Ctx, rec: PatchRecord) -> None:
    # 1. remove the SDK providers from the manifest.
    data = ctx.entry_bytes(_MAN)
    doc = axml.AxmlDoc.parse(data)

    def is_target(el):
        if el.name != "provider":
            return False
        n = doc.attr(el, "name")
        return n is not None and n.value in _REMOVE_PROVIDERS

    present = {doc.attr(el, "name").value for el in doc.elements()
              if el.name == "provider" and doc.attr(el, "name")}
    to_remove = sorted(_REMOVE_PROVIDERS & present)
    out = doc.remove_elements(is_target)
    # prove: re-parse, and that exactly the targets are gone and nothing else changed.
    after = axml.AxmlDoc.parse(out)
    after_provs = {after.attr(el, "name").value for el in after.elements()
                   if el.name == "provider" and after.attr(el, "name")}
    if _REMOVE_PROVIDERS & after_provs:
        raise PatchError("provider removal left a target provider in the manifest")
    if (present - set(to_remove)) != after_provs:
        raise PatchError("provider removal changed providers other than the targets")
    ctx.replace(_MAN, out)
    for name in to_remove:
        rec.sites.append(SiteRecord("manifest-element", f"provider {name}", "applied", None, None, "removed"))
    rec.notes.append(f"removed {len(to_remove)} SDK provider(s) from the manifest; "
                     f"{len(_REMOVE_PROVIDERS) - len(to_remove)} already absent")

    # 2. stub androidx.startup InitializationProvider.onCreate -> true (skip its initialisers).
    fps = ctx.sites.get("debloat_providers", {})
    for cls in _STUB_ONCREATE:
        key = f"{cls}->onCreate()Z"
        info = fps.get(key)
        if info is None:
            rec.sites.append(SiteRecord("dex-method", key, "skipped", None, None, "absent in this build"))
            continue
        site = SiteRecord("dex-method", key, "pending", info["sha"], None, f"{info['dex']}: RETURN_TRUE")
        rec.sites.append(site)
        ctx.add_dex_op(info["dex"], {
            "kind": "stub", "cls": cls, "name": "onCreate", "desc": "()Z",
            "expectRawSha256": info["sha"], "stub": "RETURN_TRUE",
        }, site)
