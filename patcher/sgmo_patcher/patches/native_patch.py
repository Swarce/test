"""native-patch: neutralise PairIP's native integrity DT_INIT in the game's own libraries.

``libil2cpp.so``, ``libunity.so`` and ``libnative-googlesignin.so`` each carry a PairIP-injected
``DT_INIT`` whose whole body is ``ExecuteProgram("<program>")`` — an integrity program run by the
native VM when the library loads (before the library's own ``INIT_ARRAY`` constructors). On a
repackaged APK it detects the change and crashes the process (SIGSEGV in ``libpairipcore.so``) or
sends the user to the Play Store; this is where the b05 device run died, in libunity/il2cpp load.

For each library this patch makes ``DT_INIT`` return immediately: it keeps the leading ``bti c`` (so
an indirect call with branch-target identification still lands) and writes a ``ret`` right after, so
``ExecuteProgram`` is never called. ``ExecuteProgram`` itself is untouched, so any legitimate use of
it still works. Each DT_INIT is fingerprinted against the verified build.

Off by default; enabled for the native-tamper experiments (b06+).
"""

from __future__ import annotations

import hashlib

from .base import Ctx, PatchError
from ..report import PatchRecord, SiteRecord
from .. import elf

NAME = "native-patch"
DESCRIPTION = "neutralise PairIP's integrity DT_INIT in libil2cpp/libunity/libnative-googlesignin"
DEFAULT_ON = False

# library -> sites key for its DT_INIT fingerprint
_LIBS = {
    "lib/arm64-v8a/libil2cpp.so": "libil2cpp_dt_init",
    "lib/arm64-v8a/libunity.so": "libunity_dt_init",
    "lib/arm64-v8a/libnative-googlesignin.so": "libnative_googlesignin_dt_init",
}
_BTI_C = bytes.fromhex("5f2403d5")   # aarch64 'bti c'
_RET = bytes.fromhex("c0035fd6")     # aarch64 'ret'
_FP_LEN = 0x40                        # bytes of the DT_INIT function we fingerprint


def apply(ctx: Ctx, rec: PatchRecord) -> None:
    native = ctx.sites.get("native_patches", {})
    patched = 0
    for lib, key in _LIBS.items():
        if not ctx.has(lib):
            rec.sites.append(SiteRecord("native", f"{lib} DT_INIT", "skipped", None, None, "lib absent"))
            continue
        data = bytearray(ctx.entry_bytes(lib))
        try:
            e = elf.Elf64(bytes(data))
        except elf.ElfError as ex:
            raise PatchError(f"{lib}: {ex}")
        off = e.dt_init_offset()
        if off is None:
            rec.sites.append(SiteRecord("native", f"{lib} DT_INIT", "skipped", None, None, "no DT_INIT"))
            continue
        if data[off:off + 4] != _BTI_C:
            raise PatchError(f"{lib} DT_INIT does not start with 'bti c'; unexpected build")

        actual = hashlib.sha256(bytes(data[off:off + _FP_LEN])).hexdigest()
        expected = native.get(key)
        if expected is None and not ctx.force_unverified:
            raise PatchError(f"no verified-build fingerprint for {lib} DT_INIT "
                             "(regenerate sites with tools/make_sites.py).")
        if expected is not None and actual.lower() != expected.lower():
            rec.sites.append(SiteRecord("native", f"{lib} DT_INIT", "refused", expected, actual,
                                        "differs from the verified build"))
            if not ctx.force_unverified:
                raise PatchError(f"{lib} DT_INIT does not match the verified build "
                                 f"(expected {expected[:16]}…, got {actual[:16]}…).")

        if data[off + 4:off + 8] == _RET:
            rec.sites.append(SiteRecord("native", f"{lib} DT_INIT", "unchanged", expected, actual,
                                        "already neutralised"))
            continue
        data[off + 4:off + 8] = _RET
        ctx.replace(lib, bytes(data))
        patched += 1
        rec.sites.append(SiteRecord("native", f"{lib} DT_INIT", "applied", expected, actual,
                                    f"wrote 'ret' at DT_INIT+4 (file off 0x{off + 4:x})"))

    if not patched:
        rec.status = "skipped"
    rec.notes.append("PairIP's per-library integrity programs no longer run at load; each library's "
                     "own INIT_ARRAY constructors are untouched, as is ExecuteProgram itself")
