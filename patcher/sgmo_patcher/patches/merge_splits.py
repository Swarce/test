"""merge-splits: fold the ABI split into the base and make the single APK installable.

- Copy every ``lib/arm64-v8a/*.so`` from the ``config.arm64_v8a`` split into the output.
- Clear ``manifest@requiredSplitTypes`` (point it at the pool's empty string) so the installer
  does not demand the split, and set the ``com.android.vending.splits.required`` meta-data to false.
- Drop the split's own manifest, signatures and stamp (the whole split APK is consumed; only its
  ``lib/`` entries are kept).

The base manifest edits are done through the binary-XML editor and are strictly in place (the file
length is unchanged), so they cannot disturb anything else in the manifest.
"""

from __future__ import annotations

from .base import Ctx, PatchError, check_file
from ..report import PatchRecord, SiteRecord
from .. import axml

NAME = "merge-splits"
DESCRIPTION = "merge the arm64 split into the base and clear the split requirement"
DEFAULT_ON = True

_SPLIT = "config.arm64_v8a"
_TYPE_INT_BOOLEAN = 0x12  # Res_value dataType for a boolean


def apply(ctx: Ctx, rec: PatchRecord) -> None:
    split = ctx.apk.split(_SPLIT)
    if split is None:
        # arm64 libs may already be in the base (a universal APK). If so, nothing to merge.
        if any(n.startswith("lib/arm64-v8a/") for n in ctx.plan.names()):
            rec.status = "skipped"
            rec.notes.append("no arm64 split and the base already has lib/arm64-v8a/ — nothing to merge")
            return
        raise PatchError(
            f"the '{_SPLIT}' split APK was not found and the base has no arm64 libraries. "
            "Give the config.arm64_v8a split (it carries the game's native code).")

    # 1. copy the split's lib/ entries.
    added = 0
    for e in split.entries:
        if e.is_dir or not e.name.startswith("lib/arm64-v8a/"):
            continue
        if ctx.plan.has(e.name):
            raise PatchError(f"{e.name} is in both the base and the split; refusing to overwrite.")
        ctx.plan.add_raw(split, e)
        added += 1
    rec.sites.append(SiteRecord("file", "lib/arm64-v8a/*.so", "applied", None, str(added),
                                f"{added} native libraries copied from the split"))
    if added == 0:
        raise PatchError("the split APK carried no lib/arm64-v8a/ entries.")

    # 2. edit the base manifest in place.
    man_name = "AndroidManifest.xml"
    data, _ = check_file(ctx, man_name, ctx.sites.get("files", {}).get(man_name), rec)
    doc = axml.AxmlDoc.parse(data)

    empty_idx = doc.find_string("")
    if empty_idx is None:
        raise PatchError("the manifest string pool has no empty string to point the split type at.")

    # requiredSplitTypes -> "" (this is the edit that fixes INSTALL_FAILED_MISSING_SPLIT).
    changed = []
    man = doc.find(("manifest",))
    req = doc.attr(man[0], "requiredSplitTypes") if man else None
    if req is not None and req.value not in ("", None):
        before = req.value
        doc.set_attr_string(req, empty_idx)
        changed.append("requiredSplitTypes")
        rec.sites.append(SiteRecord("manifest-attr", "manifest@requiredSplitTypes", "applied",
                                    str(before), '""'))
    else:
        rec.sites.append(SiteRecord("manifest-attr", "manifest@requiredSplitTypes", "unchanged",
                                    note="already empty or absent"))

    # com.android.vending.splits.required -> false (defence in depth; Play-Core, not the installer).
    md = doc.find(("manifest", "application", "meta-data"),
                  {"name": "com.android.vending.splits.required"})
    if md:
        val = doc.attr(md[0], "value")
        if val is not None and val.value is not False:
            doc.set_attr_typed(val, _TYPE_INT_BOOLEAN, 0)
            changed.append("splits.required")
            rec.sites.append(SiteRecord("manifest-attr",
                                        "meta-data com.android.vending.splits.required", "applied",
                                        "true", "false"))

    out = doc.to_bytes()
    if len(out) != len(data):
        raise PatchError("manifest edit changed the file length; refusing (in-place edits only).")
    if changed:
        ctx.replace(man_name, out)
    rec.notes.append(f"manifest edits: {', '.join(changed) if changed else 'none needed'}")

    # 3. (the split APK itself is simply not added; only its lib/ entries were copied.)
