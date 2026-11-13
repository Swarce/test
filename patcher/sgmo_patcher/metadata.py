"""IL2CPP ``global-metadata.dat`` string-literal table: reader and in-place rewriter.

Unity IL2CPP keeps every C# string literal in ``global-metadata.dat``. The file starts with
a little-endian header: ``uint32`` sanity (0xFAB11BAF), ``int32`` version, then a run of
``(uint32 offset, uint32 size)`` pairs describing each section. Only the first two pairs
matter here:

* ``stringLiteral`` (offset, size) -- a table of ``size / 8`` entries, each
  ``{uint32 length, uint32 dataIndex}``;
* ``stringLiteralData`` (offset, size) -- the UTF-8 bytes the entries point into.

Literal ``i`` is ``data[stringLiteralDataOffset + dataIndex : + length]``. The header layout
of these fields is identical for metadata versions 24..31, which is the range this module
accepts (Skullgirls Mobile 8.12.0 is version 31).

The only mutation is :meth:`Metadata.rewrite_literal`, which overwrites a literal's bytes in
place (see ``docs/design/patcher.md``, "Metadata rewrite"): the replacement must be no longer
than the original, and no other literal may share the original's byte range. That keeps the
file length, the header, and every other literal and byte identical, which :func:`compare`
verifies. This is what the ``redirect-api``/``redirect-cdn`` patches rely on.
"""

from __future__ import annotations

import struct
from typing import List, Optional, Set

__all__ = ["MetadataError", "Metadata", "compare"]

_SANITY = 0xFAB11BAF
_MIN_VERSION = 24
_MAX_VERSION = 31
_ENTRY = struct.Struct("<II")  # length, dataIndex


class MetadataError(Exception):
    """The metadata is not a supported ``global-metadata.dat``, or a rewrite is invalid."""


class Metadata:
    """A parsed ``global-metadata.dat`` with in-place string-literal rewriting."""

    def __init__(self):
        self._data = bytearray()
        self._orig_len = 0
        self.version = 0
        self._lit_off = 0
        self._lit_size = 0
        self._data_off = 0
        self._data_size = 0
        self._entries: List[tuple] = []
        self.changes: List[dict] = []

    @classmethod
    def parse(cls, data: bytes) -> "Metadata":
        """Parse ``data``; raises :class:`MetadataError` if it is not supported metadata."""
        self = cls()
        self._data = bytearray(data)
        self._orig_len = len(data)
        if len(data) < 24:
            raise MetadataError("file is too small to be global-metadata.dat")
        sanity, version = struct.unpack_from("<Ii", data, 0)
        if sanity != _SANITY:
            raise MetadataError(f"bad sanity 0x{sanity:08X} (expected 0x{_SANITY:08X})")
        if not _MIN_VERSION <= version <= _MAX_VERSION:
            raise MetadataError(f"unsupported metadata version {version} "
                                f"(supported: {_MIN_VERSION}..{_MAX_VERSION})")
        self.version = version
        lit_off, lit_size, data_off, data_size = struct.unpack_from("<IIII", data, 8)
        if lit_size % 8:
            raise MetadataError("string literal table size is not a multiple of 8")
        if lit_off + lit_size > len(data) or lit_off < 24:
            raise MetadataError("string literal table lies outside the file")
        if data_off + data_size > len(data) or data_off < 24:
            raise MetadataError("string literal data lies outside the file")
        self._lit_off = lit_off
        self._lit_size = lit_size
        self._data_off = data_off
        self._data_size = data_size
        count = lit_size // 8
        entries = []
        for i in range(count):
            length, data_index = _ENTRY.unpack_from(data, lit_off + i * 8)
            if data_index + length > data_size:
                raise MetadataError(f"literal #{i} runs past the string literal data section")
            entries.append((length, data_index))
        self._entries = entries
        return self

    # -- reading -----------------------------------------------------------------------

    @property
    def literal_count(self) -> int:
        """Number of string literals."""
        return len(self._entries)

    def _check_index(self, i: int) -> None:
        if not 0 <= i < len(self._entries):
            raise MetadataError(f"literal index {i} out of range (0..{len(self._entries) - 1})")

    def literal_entry(self, i: int) -> tuple:
        """The ``(length, dataIndex)`` table entry of literal ``i``."""
        self._check_index(i)
        return self._entries[i]

    def literal(self, i: int) -> bytes:
        """The raw UTF-8 bytes of literal ``i``."""
        self._check_index(i)
        length, data_index = self._entries[i]
        start = self._data_off + data_index
        return bytes(self._data[start:start + length])

    def literal_str(self, i: int) -> str:
        """Literal ``i`` decoded as UTF-8 (``errors='replace'``)."""
        return self.literal(i).decode("utf-8", "replace")

    def find_literal(self, s: str) -> List[int]:
        """Indices of every literal whose bytes exactly equal ``s`` encoded as UTF-8."""
        target = s.encode("utf-8")
        n = len(target)
        out = []
        for i, (length, _) in enumerate(self._entries):
            if length == n and self.literal(i) == target:
                out.append(i)
        return out

    def overlapping(self, i: int) -> List[int]:
        """Indices (other than ``i``) whose byte range intersects literal ``i``'s range."""
        self._check_index(i)
        length, data_index = self._entries[i]
        if length == 0:
            return []
        a, b = data_index, data_index + length
        out = []
        for j, (lj, dj) in enumerate(self._entries):
            if j == i or lj == 0:
                continue
            if max(a, dj) < min(b, dj + lj):
                out.append(j)
        return out

    # -- rewriting ---------------------------------------------------------------------

    def rewrite_literal(self, i: int, new: str) -> None:
        """Overwrite literal ``i`` with ``new`` in place.

        Raises :class:`MetadataError` if ``new`` (as UTF-8) is longer than the current
        literal, or if any other literal shares its byte range. On success the entry length
        is updated, any trailing bytes of the old range are zeroed, and a record is appended
        to :attr:`changes`.
        """
        self._check_index(i)
        length, data_index = self._entries[i]
        new_bytes = new.encode("utf-8")
        if len(new_bytes) > length:
            raise MetadataError(
                f"replacement for literal #{i} is {len(new_bytes)} bytes, "
                f"longer than the original {length} bytes")
        overlaps = self.overlapping(i)
        if overlaps:
            raise MetadataError(
                f"literal #{i} overlaps other literals {overlaps[:8]}; refusing to rewrite")
        old_str = self.literal_str(i)
        start = self._data_off + data_index
        entry_off = self._lit_off + i * 8
        self._data[start:start + len(new_bytes)] = new_bytes
        # Zero the tail of the old range so no stale bytes remain.
        for p in range(start + len(new_bytes), start + length):
            self._data[p] = 0
        struct.pack_into("<I", self._data, entry_off, len(new_bytes))
        self._entries[i] = (len(new_bytes), data_index)
        self.changes.append({
            "index": i,
            "old": old_str,
            "new": new,
            "fileOffset": start,
            "tableEntryOffset": entry_off,
        })

    def to_bytes(self) -> bytes:
        """The current bytes; always the same length as the parsed input."""
        out = bytes(self._data)
        assert len(out) == self._orig_len, "metadata edit changed the file length"
        return out

    def __repr__(self) -> str:
        return f"Metadata(version={self.version}, literals={self.literal_count})"


def _diff_positions(a: bytes, b: bytes, start: int, end: int, label: str,
                    out: List[str], limit: int = 3) -> None:
    """Append up to ``limit`` violations where ``a`` and ``b`` differ within [start, end)."""
    found = 0
    p = start
    while p < end:
        if a[p] != b[p]:
            out.append(f"{label}: byte {p} differs (0x{a[p]:02X} -> 0x{b[p]:02X})")
            found += 1
            if found >= limit:
                out.append(f"{label}: more differences past byte {p}")
                return
        p += 1


def compare(original: bytes, patched: bytes, allowed: Set[int]) -> List[str]:
    """Human-readable violations if ``patched`` differs from ``original`` anywhere other than
    the string literals in ``allowed``. An empty list means the two files are identical except
    for those literals' own bytes and length fields.

    For an allowed literal, only the data within its original byte range and its table
    ``length`` field may change; its ``dataIndex`` and every other byte must be unchanged.
    """
    out: List[str] = []
    if len(original) != len(patched):
        out.append(f"length changed: {len(original)} -> {len(patched)}")
        return out

    orig = Metadata.parse(original)
    try:
        patch = Metadata.parse(patched)
    except MetadataError as exc:
        return [f"patched metadata does not parse: {exc}"]

    for name, a, b in (("version", orig.version, patch.version),
                       ("stringLiteralOffset", orig._lit_off, patch._lit_off),
                       ("stringLiteralSize", orig._lit_size, patch._lit_size),
                       ("stringLiteralDataOffset", orig._data_off, patch._data_off),
                       ("stringLiteralDataSize", orig._data_size, patch._data_size)):
        if a != b:
            out.append(f"header field {name} changed: {a} -> {b}")
    if out:
        return out

    count = orig.literal_count
    # Byte ranges that are permitted to differ: each allowed literal's table length field
    # (4 bytes) and its original data range.
    excepted: List[tuple] = []
    for i in sorted(allowed):
        if not 0 <= i < count:
            out.append(f"allowed index {i} is out of range")
            continue
        length, data_index = orig.literal_entry(i)
        p_length, p_data_index = patch.literal_entry(i)
        if p_data_index != data_index:
            out.append(f"allowed literal #{i}: dataIndex changed {data_index} -> {p_data_index}")
        if p_length > length:
            out.append(f"allowed literal #{i}: length grew {length} -> {p_length}")
        entry_off = orig._lit_off + i * 8
        excepted.append((entry_off, entry_off + 4))  # the length field only
        start = orig._data_off + data_index
        excepted.append((start, start + length))     # the original data range

    # Merge the excepted ranges, then confirm every byte outside them is identical.
    excepted.sort()
    merged: List[list] = []
    for s, e in excepted:
        if merged and s <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], e)
        else:
            merged.append([s, e])

    cursor = 0
    n = len(original)
    for s, e in merged:
        if s > cursor:
            _diff_positions(original, patched, cursor, s, "unexpected change", out)
        cursor = max(cursor, e)
    if cursor < n:
        _diff_positions(original, patched, cursor, n, "unexpected change", out)

    # Belt and braces: every literal not in allowed must be byte-for-byte identical.
    for i in range(count):
        if i in allowed:
            continue
        if orig.literal_entry(i) != patch.literal_entry(i):
            out.append(f"literal #{i}: table entry changed {orig.literal_entry(i)} -> "
                       f"{patch.literal_entry(i)}")
        elif orig.literal(i) != patch.literal(i):
            out.append(f"literal #{i}: data bytes changed")
            if len([m for m in out if m.startswith("literal #")]) > 8:
                out.append("... more literal changes")
                break

    return out
