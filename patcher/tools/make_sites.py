"""Generate a verified-build fingerprint file: patcher/sgmo_patcher/sites/<versionCode>.json.

Run this once per game version, against a copy you trust, to pin the build the patcher may touch:

    python patcher/tools/make_sites.py <your-skullgirls.xapk>   > patcher/sgmo_patcher/sites/266.json

It records whole-file hashes (manifest, metadata, every dex) and the PairIP gate method
fingerprints (raw-insns SHA-256 and which dex each lives in). Output is data — hashes, offsets and
short identifiers — never game code. Needs the Java helper (for dex fingerprints) and nothing else.
"""

from __future__ import annotations

import hashlib
import io
import json
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from sgmo_patcher import axml, dex  # noqa: E402

# The PairIP Java gates b01 stubs; keyed as "<cls>-><name><desc>".
_GATE_KEYS = {
    "Lcom/pairip/SignatureCheck;->verifyIntegrity(Landroid/content/Context;)V",
    "Lcom/pairip/licensecheck/LicenseClient;->checkLicense(Landroid/content/Context;)V",
}
# Method the embed-server patch injects the Boot call into (M2).
_INJECT_KEY = "Lcom/pairip/application/Application;->attachBaseContext(Landroid/content/Context;)V"
# PairIP bootstrap methods that run its native VM programs (b03 native-tamper work).
_BOOTSTRAP_KEYS = {
    "Lcom/pairip/StartupLauncher;->launch()V",
}
# SDK auto-init ContentProviders stubbed so their classes (which read PairIP's now-null string
# fields) never load at boot. onCreate returns true (b04). Telemetry/ads are dropped offline anyway.
_DEBLOAT_PROVIDER_KEYS = {
    "Lcom/google/firebase/provider/FirebaseInitProvider;->onCreate()Z",
    "Lcom/google/android/gms/ads/MobileAdsInitProvider;->onCreate()Z",
    "Lcom/vungle/ads/VungleProvider;->onCreate()Z",
    "Lio/bidmachine/BidMachineInitProvider;->onCreate()Z",
    "Landroidx/startup/InitializationProvider;->onCreate()Z",
}


def _base_apk_bytes(path: str) -> bytes:
    data = Path(path).read_bytes()
    if path.endswith(".apk"):
        return data
    z = zipfile.ZipFile(io.BytesIO(data))
    for n in z.namelist():
        if n.endswith(".apk") and "config" not in n and "split" not in n.lower():
            return z.read(n)
    raise SystemExit(f"could not find a base APK inside {path}")


def _lib_bytes(path: str, lib: str) -> bytes | None:
    """A ``lib/arm64-v8a/<lib>`` from the base APK or the arm64 split inside an xapk/apks."""
    entry = f"lib/arm64-v8a/{lib}"
    data = Path(path).read_bytes()
    if path.endswith(".apk"):
        z = zipfile.ZipFile(io.BytesIO(data))
        return z.read(entry) if entry in z.namelist() else None
    outer = zipfile.ZipFile(io.BytesIO(data))
    for n in outer.namelist():
        if n.endswith(".apk"):
            inner = zipfile.ZipFile(io.BytesIO(outer.read(n)))
            if entry in inner.namelist():
                return inner.read(entry)
    return None


def _native_patches(path: str) -> dict:
    """Fingerprint the PairIP DT_INIT function (0x40 bytes) of each affected library."""
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
    from sgmo_patcher import elf  # noqa: E402
    libs = {
        "libil2cpp.so": "libil2cpp_dt_init",
        "libunity.so": "libunity_dt_init",
        "libnative-googlesignin.so": "libnative_googlesignin_dt_init",
    }
    out: dict = {}
    for lib, key in libs.items():
        so = _lib_bytes(path, lib)
        if so is None:
            continue
        e = elf.Elf64(so)
        off = e.dt_init_offset()
        if off is None:
            continue
        out[key] = hashlib.sha256(so[off:off + 0x40]).hexdigest()
    return out


def main(argv) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    base = zipfile.ZipFile(io.BytesIO(_base_apk_bytes(argv[1])))

    def sha(b: bytes) -> str:
        return hashlib.sha256(b).hexdigest()

    man = base.read("AndroidManifest.xml")
    info = axml.manifest_info(man)
    files = {"AndroidManifest.xml": sha(man)}
    meta = "assets/bin/Data/Managed/Metadata/global-metadata.dat"
    files[meta] = sha(base.read(meta))
    dex_names = sorted(n for n in base.namelist() if n.startswith("classes") and n.endswith(".dex"))
    for n in dex_names:
        files[n] = sha(base.read(n))

    gates: dict[str, dict] = {}
    bootstrap: dict[str, dict] = {}
    debloat: dict[str, dict] = {}
    boot_inject: dict[str, object] = {}
    for n in dex_names:
        for m in dex.dex_methods(base.read(n)):
            key = f"{m['cls']}->{m['name']}{m['desc']}"
            if key in _GATE_KEYS:
                gates[key] = {"sha": m["rawSha256"], "dex": n}
            if key in _BOOTSTRAP_KEYS:
                bootstrap[key] = {"sha": m["rawSha256"], "dex": n}
            if key in _DEBLOAT_PROVIDER_KEYS:
                debloat[key] = {"sha": m["rawSha256"], "dex": n}
            if key == _INJECT_KEY:
                boot_inject[key] = m["rawSha256"]
                boot_inject[f"{key}_dex"] = n

    missing = _GATE_KEYS - set(gates)
    if missing:
        raise SystemExit(f"gate method(s) not found: {', '.join(sorted(missing))}")
    if _INJECT_KEY not in boot_inject:
        raise SystemExit("Application.attachBaseContext not found")

    out = {
        "package": info["package"],
        "versionCode": info["versionCode"],
        "versionName": info["versionName"],
        "files": files,
        "pairip_gates": gates,
        "pairip_bootstrap": bootstrap,
        "debloat_providers": debloat,
        "native_patches": _native_patches(argv[1]),
        "boot_inject": boot_inject,
    }
    print(json.dumps(out, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
