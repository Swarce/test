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


def _base_apk_bytes(path: str) -> bytes:
    data = Path(path).read_bytes()
    if path.endswith(".apk"):
        return data
    z = zipfile.ZipFile(io.BytesIO(data))
    for n in z.namelist():
        if n.endswith(".apk") and "config" not in n and "split" not in n.lower():
            return z.read(n)
    raise SystemExit(f"could not find a base APK inside {path}")


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
    for n in dex_names:
        for m in dex.dex_methods(base.read(n)):
            key = f"{m['cls']}->{m['name']}{m['desc']}"
            if key in _GATE_KEYS:
                gates[key] = {"sha": m["rawSha256"], "dex": n}

    missing = _GATE_KEYS - set(gates)
    if missing:
        raise SystemExit(f"gate method(s) not found: {', '.join(sorted(missing))}")

    out = {
        "package": info["package"],
        "versionCode": info["versionCode"],
        "versionName": info["versionName"],
        "files": files,
        "pairip_gates": gates,
    }
    print(json.dumps(out, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
