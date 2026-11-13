"""embed-server (M2): put the offline server inside the APK so no PC is needed.

Adds three things to the APK and wires them up:
- ``lib/arm64-v8a/libsgmserver.so`` — the Rust server (built from ``server/``), provided via
  ``--server-so``.
- ``assets/sgm-content.json`` — the game content the server reads, extracted from the player's own
  copy and merged into one JSON object, stored (uncompressed) so the server can read it in place.
- a new ``classes<N>.dex`` holding ``sgm.offline.Boot`` (loadLibrary + nativeStart), plus a single
  ``invoke-static {}, Lsgm/offline/Boot;->start()V`` injected at the top of the app's
  ``Application.attachBaseContext`` (fingerprinted).

When this patch is on, ``redirect-api`` still points the client at ``127.0.0.1:<port>`` — only now
the server answering there lives in the game's own process, started as the app boots.
"""

from __future__ import annotations

import os
from pathlib import Path

from .base import Ctx, PatchError
from ..report import PatchRecord, SiteRecord
from .. import dex, javahelper

NAME = "embed-server"
DESCRIPTION = "embed the offline server .so, its content and a bootstrap in the APK (no PC needed)"
DEFAULT_ON = False  # opt-in: needs --server-so and the content extractor

_APP_CLASS = "Lcom/pairip/application/Application;"
_ATTACH = ("attachBaseContext", "(Landroid/content/Context;)V")
_BOOT_INVOKE = "Lsgm/offline/Boot;->start()V"


def _next_dex_name(ctx: Ctx) -> str:
    n = 1
    while True:
        name = "classes.dex" if n == 1 else f"classes{n}.dex"
        if not ctx.has(name):
            return name
        n += 1


def apply(ctx: Ctx, rec: PatchRecord) -> None:
    so = ctx.config.get("server_so")
    content_json = ctx.config.get("content_json")
    if not so or not os.path.exists(so):
        raise PatchError("embed-server needs --server-so pointing at a built libsgmserver.so "
                         "(build it from server/ with the Android NDK; see docs/DEV-LOOP.md).")
    if not content_json or not os.path.exists(content_json):
        raise PatchError("embed-server needs the combined content JSON (--content-json), produced by "
                         "tools/content-gen/extract_content.py --combined.")

    # 1. the native server library
    ctx.plan.add_bytes("lib/arm64-v8a/libsgmserver.so", Path(so).read_bytes(), method="deflate")
    rec.sites.append(SiteRecord("file", "lib/arm64-v8a/libsgmserver.so", "applied", None,
                                str(os.path.getsize(so)), "embedded server"))

    # 2. the content, stored uncompressed so the server reads it straight from the APK
    data = Path(content_json).read_bytes()
    ctx.plan.add_bytes("assets/sgm-content.json", data, method="stored")
    rec.sites.append(SiteRecord("file", "assets/sgm-content.json", "applied", None,
                                str(len(data)), "bundled game content (stored)"))

    # 3. the Boot class in its own dex
    boot_bytes = javahelper.make_boot_dex()
    dex_name = _next_dex_name(ctx)
    ctx.plan.add_bytes(dex_name, boot_bytes, method="deflate")
    rec.sites.append(SiteRecord("file", dex_name, "applied", None, str(len(boot_bytes)),
                                "sgm.offline.Boot"))

    # 4. inject the boot call into Application.attachBaseContext (fingerprinted)
    fp = ctx.sites.get("boot_inject", {}).get(f"{_APP_CLASS}->{_ATTACH[0]}{_ATTACH[1]}")
    target_dex = (ctx.sites.get("boot_inject", {})
                  .get(f"{_APP_CLASS}->{_ATTACH[0]}{_ATTACH[1]}_dex", "classes2.dex"))
    if fp is None and not ctx.force_unverified:
        raise PatchError("no verified-build fingerprint for Application.attachBaseContext "
                         "(regenerate sites with tools/make_sites.py).")
    if not ctx.has(target_dex):
        raise PatchError(f"{target_dex} is not in the APK")
    out, result = dex.dex_inject_call(
        ctx.entry_bytes(target_dex),
        cls=_APP_CLASS, name=_ATTACH[0], desc=_ATTACH[1],
        expect_raw_sha256=fp, invoke=_BOOT_INVOKE, required=True)
    if out is None:
        rec.sites.append(SiteRecord("dex-method", f"{_APP_CLASS}->attachBaseContext", "refused",
                                    fp, result.get("actualRawSha256"), result.get("status")))
        raise PatchError("could not inject the boot call: Application.attachBaseContext did not "
                         "match the verified build.")
    ctx.replace(target_dex, out)
    rec.sites.append(SiteRecord("dex-method", f"{_APP_CLASS}->attachBaseContext", "applied",
                                fp, result.get("actualRawSha256"), f"{target_dex}: +Boot.start()"))
    rec.notes.append(f"server in-process on 127.0.0.1:{ctx.config.get('port', 47820)}; "
                     "content read from the APK; saves under /data/data/<pkg>/files/sgm")
