"""Command-line entry point.

    python -m sgmo_patcher keygen                         create the signing key (once)
    python -m sgmo_patcher patch <input...> -o out.apk    patch the player's copy
    python -m sgmo_patcher verify <apk>                   check an APK's signatures and alignment
    python -m sgmo_patcher inspect <input...>             identify the copy; list patches

Run from the ``patcher/`` folder, or with ``PYTHONPATH=patcher``.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from . import __version__
from . import inputs, sign, zipio, axml
from .patcher import patch, PatchOptions, PatchError
from .patches import ALL as ALL_PATCHES


def _cmd_keygen(a) -> int:
    if a.keystore:
        ks = Path(a.keystore)
        pf = ks.with_suffix(".pass")          # password beside the chosen keystore, not the default
    else:
        ks, pf = sign.keystore_path(), sign.pass_file_path()
    if ks.exists():
        print(f"keystore already exists: {ks}")
        return 0
    path = sign.keygen(ks, pf)
    print(f"created signing key: {path}")
    print(f"password file:       {pf}")
    print("keep these; every build uses them so new APKs install over the old one.")
    return 0


def _cmd_patch(a) -> int:
    keystore = Path(a.keystore) if a.keystore else None
    pass_file = Path(a.pass_file) if a.pass_file else (
        keystore.with_suffix(".pass") if keystore else None)
    opts = PatchOptions(
        port=a.port,
        enable=a.enable or [],
        disable=a.disable or [],
        force_unverified=a.force_unverified,
        server_so=a.server_so,
        content_json=a.content_json,
        keystore=keystore,
        pass_file=pass_file,
        min_sdk=a.min_sdk,
    )
    try:
        report = patch(a.inputs, a.out, opts, tool_version=__version__)
    except (PatchError, inputs.InputError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    print(report.to_text())
    print(f"wrote {a.out}" if report.ok else "patch finished with problems (see the report)")
    return 0 if report.ok else 2


def _cmd_verify(a) -> int:
    v = sign.verify(a.apk, min_sdk=a.min_sdk)
    align = zipio.verify_alignment(a.apk)
    print(f"verified: {v.get('verified')}  v1={v.get('v1')} v2={v.get('v2')} v3={v.get('v3')}")
    print(f"cert sha256: {v.get('certSha256')}")
    print(f"alignment violations: {align or 'none'}")
    extra = sign.apksigner_verify(a.apk, min_sdk=a.min_sdk)
    if extra is not None:
        print(f"apksigner (independent): {extra}")
    ok = bool(v.get("verified")) and not align
    return 0 if ok else 2


def _cmd_inspect(a) -> int:
    with inputs.load(a.inputs, manifest_reader=axml.manifest_info) as apk:
        print(f"package      {apk.package}")
        print(f"version      {apk.version_name} (versionCode {apk.version_code})")
        print(f"base         {apk.sources[0]}")
        for name, src in zip(apk.split_names, apk.sources[1:]):
            print(f"split        {name}  ({src})")
        for w in apk.warnings:
            print(f"warning      {w}")
    print("\npatches:")
    for m in ALL_PATCHES:
        print(f"  {'on ' if m.DEFAULT_ON else 'off'}  {m.NAME:20} {m.DESCRIPTION}")
    return 0


def main(argv=None) -> int:
    p = argparse.ArgumentParser(prog="sgmo_patcher",
                                description="Turn your own Skullgirls Mobile copy into an offline, re-signed APK.")
    p.add_argument("--version", action="version", version=f"%(prog)s {__version__}")
    sub = p.add_subparsers(dest="cmd", required=True)

    pk = sub.add_parser("keygen", help="create the signing key")
    pk.add_argument("--keystore", help="keystore path (default ~/.config/sgm-offline/...)")
    pk.set_defaults(fn=_cmd_keygen)

    pp = sub.add_parser("patch", help="patch the player's copy")
    pp.add_argument("inputs", nargs="+", help="xapk / apks / folder / base.apk + split apks")
    pp.add_argument("-o", "--out", required=True, help="output APK path")
    pp.add_argument("--port", type=int, default=47820, help="local server port (default 47820)")
    pp.add_argument("--enable", action="append", help="force a patch on (repeatable)")
    pp.add_argument("--disable", action="append", help="force a patch off (repeatable)")
    pp.add_argument("--force-unverified", action="store_true",
                    help="patch even if the copy is not the fingerprinted 8.12.0 build")
    pp.add_argument("--keystore", help="signing keystore (default ~/.config/sgm-offline/...)")
    pp.add_argument("--pass-file", help="keystore password file (default: alongside the keystore)")
    pp.add_argument("--server-so", help="built libsgmserver.so, to embed the server (enables embed-server)")
    pp.add_argument("--content-json", help="combined content JSON to bundle (for embed-server)")
    pp.add_argument("--min-sdk", type=int, default=24)
    pp.set_defaults(fn=_cmd_patch)

    pv = sub.add_parser("verify", help="check an APK's signatures and alignment")
    pv.add_argument("apk")
    pv.add_argument("--min-sdk", type=int, default=24)
    pv.set_defaults(fn=_cmd_verify)

    pi = sub.add_parser("inspect", help="identify the copy and list patches")
    pi.add_argument("inputs", nargs="+")
    pi.set_defaults(fn=_cmd_inspect)

    a = p.parse_args(argv)
    try:
        return a.fn(a)
    except inputs.InputError as e:
        print(f"error: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
