"""Just enough ELF64 (AArch64) parsing to find a shared object's ``DT_INIT`` entry point.

Standard library only. Used to locate PairIP's integrity ``DT_INIT`` in ``libil2cpp.so`` so the
native patch can neutralise it.
"""

from __future__ import annotations

import struct

PT_LOAD = 1
PT_DYNAMIC = 2
DT_NULL = 0
DT_INIT = 12


class ElfError(Exception):
    pass


class Elf64:
    def __init__(self, data: bytes):
        if data[:4] != b"\x7fELF" or data[4] != 2:
            raise ElfError("not a 64-bit ELF")
        self.data = data
        self.e_phoff = struct.unpack_from("<Q", data, 0x20)[0]
        self.e_phentsize = struct.unpack_from("<H", data, 0x36)[0]
        self.e_phnum = struct.unpack_from("<H", data, 0x38)[0]
        self.loads: list[tuple[int, int, int]] = []  # (vaddr, offset, filesz)
        self.dyn: tuple[int, int] | None = None       # (offset, size)
        for i in range(self.e_phnum):
            ph = self.e_phoff + i * self.e_phentsize
            if ph + 56 > len(data):
                raise ElfError("program header out of range")
            p_type = struct.unpack_from("<I", data, ph)[0]
            p_offset = struct.unpack_from("<Q", data, ph + 8)[0]
            p_vaddr = struct.unpack_from("<Q", data, ph + 16)[0]
            p_filesz = struct.unpack_from("<Q", data, ph + 32)[0]
            if p_type == PT_LOAD:
                self.loads.append((p_vaddr, p_offset, p_filesz))
            elif p_type == PT_DYNAMIC:
                self.dyn = (p_offset, p_filesz)

    def vaddr_to_off(self, vaddr: int) -> int | None:
        for va, off, fs in self.loads:
            if va <= vaddr < va + fs:
                return off + (vaddr - va)
        return None

    def dt_init_vaddr(self) -> int | None:
        if self.dyn is None:
            return None
        off, size = self.dyn
        o = off
        end = off + size
        while o + 16 <= end and o + 16 <= len(self.data):
            tag, val = struct.unpack_from("<qQ", self.data, o)
            o += 16
            if tag == DT_INIT:
                return val
            if tag == DT_NULL:
                break
        return None

    def dt_init_offset(self) -> int | None:
        v = self.dt_init_vaddr()
        return None if v is None else self.vaddr_to_off(v)
