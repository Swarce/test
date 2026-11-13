"""redirect-api: point the game-API host literals at the local server.

The client picks a backend host from a small set of string literals in ``global-metadata.dat`` and
defaults to the **Dev** environment, not prod, so every game-API host it can select is rewritten to
``http://127.0.0.1:<port>``. The CDN host is left alone in b01 (the official CDN is still alive).

Each literal is rewritten in place (shorter string, same dataIndex) and the whole file is checked
with :func:`sgmo_patcher.metadata.compare` so nothing else moved.
"""

from __future__ import annotations

from .base import Ctx, PatchError, check_file
from ..report import PatchRecord, SiteRecord
from .. import metadata

NAME = "redirect-api"
DESCRIPTION = "rewrite the game-API host literals to the local server"
DEFAULT_ON = True

_META = "assets/bin/Data/Managed/Metadata/global-metadata.dat"

# The game-API hosts, by the environment that selects them. Prod is required; the rest are the
# fallbacks the client can land on (the default is Dev). The 'Local' host (http://localhost:9000) is
# deliberately absent: at 21 bytes it is shorter than the 22-byte target and cannot be rewritten in
# place, and it is reachable only from the in-game debug UI.
_API_HOSTS = {
    "https://skgm-prod-game-api.skullgirlsmobile.com": "prod (AutumnRelease)",
    "https://skgm-dev-game-api.skgminternal.com": "dev (the default fallback)",
    "https://skgm-qa-game-api.skgminternal.com": "qa",
    "https://skgm-test-game-api.skgminternal.com": "staging/test",
    "https://gghukasyan-ws.eu.ngrok.io": "live-debug",
}
_REQUIRED = "https://skgm-prod-game-api.skullgirlsmobile.com"


def apply(ctx: Ctx, rec: PatchRecord) -> None:
    port = ctx.config.get("port", 47820)
    target = f"http://127.0.0.1:{port}"  # no trailing slash: SetBaseUrl appends "/v1"
    if len(target) > len(_REQUIRED):
        raise PatchError(f"server target {target!r} is longer than the prod host literal; "
                         "use a shorter port so the in-place rewrite fits.")

    data, _ = check_file(ctx, _META, ctx.sites.get("files", {}).get(_META), rec)
    md = metadata.Metadata.parse(data)
    if md.version not in (24, 25, 26, 27, 28, 29, 30, 31):
        raise PatchError(f"unexpected global-metadata version {md.version}")

    changed_indices = []
    for host, role in _API_HOSTS.items():
        idxs = md.find_literal(host)
        if not idxs:
            status = "skipped"
            rec.sites.append(SiteRecord("metadata-literal", f"{host} [{role}]", status,
                                        host, None, "literal not found"))
            if host == _REQUIRED:
                raise PatchError(f"the prod game-API host literal {host!r} was not found in the "
                                 "metadata; this is not the expected build.")
            continue
        for i in idxs:
            if len(target) > md.literal_entry(i)[0]:
                if host == _REQUIRED:
                    raise PatchError(f"{target!r} does not fit literal #{i} ({host!r}).")
                rec.sites.append(SiteRecord("metadata-literal", f"#{i} {host} [{role}]", "skipped",
                                            host, None, "target longer than the literal"))
                continue
            md.rewrite_literal(i, target)
            changed_indices.append(i)
            rec.sites.append(SiteRecord("metadata-literal", f"#{i} {host} [{role}]", "applied",
                                        host, target))

    if not changed_indices:
        raise PatchError("no game-API host literal was rewritten.")

    out = md.to_bytes()
    violations = metadata.compare(data, out, set(changed_indices))
    if violations:
        raise PatchError("metadata rewrite disturbed bytes outside the target literals: "
                         + "; ".join(violations[:5]))
    ctx.replace(_META, out)  # the entry is deflated; the writer recomputes its CRC/size
    rec.notes.append(f"rewrote {len(changed_indices)} host literal(s) to {target}; "
                     "CDN left pointing at the official server (b01)")
