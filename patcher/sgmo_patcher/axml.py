"""Android binary XML (``AndroidManifest.xml``) parser and in-place typed-value editor.

A compiled ``AndroidManifest.xml`` is a chain of chunks. The outer chunk (type
``RES_XML_TYPE`` 0x0003) wraps a string pool (0x0001), an optional resource-map chunk
(0x0180) and then a flat stream of node chunks: namespace start/end (0x0100/0x0101),
element start/end (0x0102/0x0103) and CDATA (0x0104). Every chunk begins with a generic
header ``{uint16 type, uint16 headerSize, uint32 size}`` so an unknown chunk can be skipped
by its size.

This module reads that structure into :class:`AxmlDoc` (holding a mutable ``bytearray`` copy
of the file) and lets a patch change attribute values **in place** without resizing any
chunk: point a string attribute at an existing pool string, or overwrite a ``Res_value``'s
type and data. :meth:`AxmlDoc.to_bytes` therefore always returns a buffer of exactly the
input length. Nothing here re-encodes the string pool or moves a chunk, which is all the
manifest patches for this project need (see ``docs/design/patcher.md``, "Manifest edits").

Only the parts real manifests use are supported; anything malformed, truncated or outside a
chunk's declared bounds raises :class:`AxmlError`.
"""

from __future__ import annotations

import difflib
import struct
from typing import Optional

__all__ = [
    "AxmlError",
    "Attr",
    "Element",
    "AxmlDoc",
    "manifest_info",
    "diff_xml",
    "ANDROID_NS",
]

ANDROID_NS = "http://schemas.android.com/apk/res/android"

# Chunk types.
_RES_XML_TYPE = 0x0003
_RES_STRING_POOL_TYPE = 0x0001
_RES_XML_RESOURCE_MAP_TYPE = 0x0180
_RES_XML_START_NAMESPACE = 0x0100
_RES_XML_END_NAMESPACE = 0x0101
_RES_XML_START_ELEMENT = 0x0102
_RES_XML_END_ELEMENT = 0x0103
_RES_XML_CDATA = 0x0104

# String pool flags.
_UTF8_FLAG = 0x0100

# Res_value data types (a subset; others pass through as their raw int).
TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_HEX = 0x11
TYPE_INT_BOOLEAN = 0x12

_NO_ENTRY = 0xFFFFFFFF

_CHUNK_HEADER = struct.Struct("<HHI")  # type, headerSize, size


class AxmlError(Exception):
    """The binary XML is malformed, truncated, or an edit is out of range."""


def _u16(data: bytes, off: int) -> int:
    if off + 2 > len(data):
        raise AxmlError(f"truncated: need 2 bytes at offset {off}")
    return struct.unpack_from("<H", data, off)[0]


def _u32(data: bytes, off: int) -> int:
    if off + 4 > len(data):
        raise AxmlError(f"truncated: need 4 bytes at offset {off}")
    return struct.unpack_from("<I", data, off)[0]


class Attr:
    """One attribute of an element start node (a 20-byte ``ResXMLTree_attribute``).

    ``offset`` is the absolute file offset of that 20-byte struct, which is where the
    in-place editors write. ``value`` decodes the typed value lazily from the owning
    document's string pool.
    """

    __slots__ = ("ns", "name", "resid", "raw_value_idx", "type", "data", "offset", "_doc")

    def __init__(self, doc: "AxmlDoc", ns: Optional[str], name: str, resid: Optional[int],
                 raw_value_idx: int, type_: int, data: int, offset: int):
        self._doc = doc
        self.ns = ns
        self.name = name
        self.resid = resid
        self.raw_value_idx = raw_value_idx
        self.type = type_
        self.data = data
        self.offset = offset

    @property
    def value(self):
        """The decoded value: str for TYPE_STRING, bool for TYPE_INT_BOOLEAN, int for the
        integer types, ``'@0x%08X'`` for a reference, else the raw ``data`` int."""
        t = self.type
        if t == TYPE_STRING:
            strings = self._doc.strings
            if not 0 <= self.data < len(strings):
                raise AxmlError(f"attribute {self.name!r} string index {self.data} out of range")
            return strings[self.data]
        if t == TYPE_INT_BOOLEAN:
            return self.data != 0
        if t in (TYPE_INT_DEC, TYPE_INT_HEX):
            return self.data
        if t == TYPE_REFERENCE:
            return "@0x%08X" % self.data
        return self.data

    def __repr__(self) -> str:
        return f"Attr(ns={self.ns!r}, name={self.name!r}, type=0x{self.type:02X}, value={self.value!r})"


class Element:
    """An element start node in document order."""

    __slots__ = ("name", "ns", "depth", "path", "attrs", "offset", "line")

    def __init__(self, name: str, ns: Optional[str], depth: int, path: tuple,
                 attrs: list, offset: int, line: int):
        self.name = name
        self.ns = ns
        self.depth = depth
        self.path = path
        self.attrs = attrs
        self.offset = offset
        self.line = line

    def __repr__(self) -> str:
        return f"Element(path={self.path!r}, attrs={len(self.attrs)})"


class AxmlDoc:
    """A parsed binary ``AndroidManifest.xml`` with in-place typed-value editing."""

    def __init__(self):
        self._data = bytearray()
        self._orig_len = 0
        self.strings: list[str] = []
        self.utf8 = False
        self.resource_ids: list[int] = []
        self._elements: list[Element] = []

    # -- parsing -----------------------------------------------------------------------

    @classmethod
    def parse(cls, data: bytes) -> "AxmlDoc":
        """Parse ``data`` into an :class:`AxmlDoc`. Raises :class:`AxmlError` if malformed."""
        self = cls()
        self._data = bytearray(data)
        self._orig_len = len(data)
        buf = self._data
        if len(buf) < _CHUNK_HEADER.size:
            raise AxmlError("file is too small to be binary XML")
        xtype, xhdr, xsize = _CHUNK_HEADER.unpack_from(buf, 0)
        if xtype != _RES_XML_TYPE:
            raise AxmlError(f"not binary XML: top chunk type is 0x{xtype:04X}")
        if xhdr < _CHUNK_HEADER.size or xsize < xhdr or xsize > len(buf):
            raise AxmlError("top chunk size is out of range (truncated file?)")
        end = xsize

        pool_done = False
        stack: list[str] = []
        line = 1
        off = xhdr
        while off + _CHUNK_HEADER.size <= end:
            ctype, chdr, csize = _CHUNK_HEADER.unpack_from(buf, off)
            if chdr < _CHUNK_HEADER.size or csize < chdr or off + csize > end:
                raise AxmlError(f"chunk at offset {off} has an invalid size")
            if ctype == _RES_STRING_POOL_TYPE:
                if pool_done:
                    raise AxmlError("more than one string pool chunk")
                self._parse_string_pool(off, chdr, csize)
                pool_done = True
            elif ctype == _RES_XML_RESOURCE_MAP_TYPE:
                self.resource_ids = [
                    _u32(buf, off + chdr + 4 * i) for i in range((csize - chdr) // 4)
                ]
            elif ctype == _RES_XML_START_ELEMENT:
                el, line = self._parse_element_start(off, csize, stack)
                self._elements.append(el)
                stack.append(el.name)
            elif ctype == _RES_XML_END_ELEMENT:
                if not stack:
                    raise AxmlError(f"element end at offset {off} with no open element")
                stack.pop()
            elif ctype in (_RES_XML_START_NAMESPACE, _RES_XML_END_NAMESPACE, _RES_XML_CDATA):
                pass  # not needed; skipped by size
            # unknown chunks are skipped by their size
            off += csize
        if not pool_done:
            raise AxmlError("no string pool chunk")
        return self

    def _parse_string_pool(self, off: int, chdr: int, csize: int) -> None:
        buf = self._data
        if chdr < 28:
            raise AxmlError("string pool header is too small")
        string_count, style_count, flags, strings_start, _styles_start = struct.unpack_from(
            "<IIIII", buf, off + 8)
        self.utf8 = bool(flags & _UTF8_FLAG)
        offsets_at = off + 28
        if offsets_at + 4 * string_count > off + csize:
            raise AxmlError("string pool offset table runs past the chunk")
        base = off + strings_start
        if not off + chdr <= base <= off + csize:
            raise AxmlError("string pool strings start is out of range")
        limit = off + csize
        strings: list[str] = []
        for i in range(string_count):
            rel = _u32(buf, offsets_at + 4 * i)
            pos = base + rel
            if not base <= pos < limit:
                raise AxmlError(f"string #{i} offset is out of range")
            strings.append(self._decode_string(pos, limit))
        self.strings = strings

    def _decode_string(self, pos: int, limit: int) -> str:
        buf = self._data
        if self.utf8:
            _char_count, pos = _read_len8(buf, pos, limit)  # char count (unused)
            byte_count, pos = _read_len8(buf, pos, limit)
            if pos + byte_count > limit:
                raise AxmlError("UTF-8 string runs past the pool")
            return buf[pos:pos + byte_count].decode("utf-8", "replace")
        unit_count, pos = _read_len16(buf, pos, limit)
        nbytes = unit_count * 2
        if pos + nbytes > limit:
            raise AxmlError("UTF-16 string runs past the pool")
        return bytes(buf[pos:pos + nbytes]).decode("utf-16-le", "replace")

    def _parse_element_start(self, off: int, csize: int, stack: list) -> tuple:
        buf = self._data
        if csize < 36:
            raise AxmlError(f"element start at offset {off} is too small")
        line = _u32(buf, off + 8)
        ns_idx = _u32(buf, off + 16)
        name_idx = _u32(buf, off + 20)
        attr_start = _u16(buf, off + 24)
        attr_size = _u16(buf, off + 26)
        attr_count = _u16(buf, off + 28)
        name = self._string(name_idx)
        ns = None if ns_idx == _NO_ENTRY else self._string(ns_idx)
        attrs_base = off + 16 + attr_start
        if attr_size < 20 and attr_count:
            raise AxmlError(f"element at offset {off} has attribute size {attr_size}")
        if attrs_base + attr_count * attr_size > off + csize:
            raise AxmlError(f"element at offset {off} attributes run past the chunk")
        attrs = []
        for k in range(attr_count):
            a_off = attrs_base + k * attr_size
            a_ns = _u32(buf, a_off)
            a_name = _u32(buf, a_off + 4)
            a_raw = _u32(buf, a_off + 8)
            a_type = buf[a_off + 15]
            a_data = _u32(buf, a_off + 16)
            resid = self.resource_ids[a_name] if a_name < len(self.resource_ids) else None
            attrs.append(Attr(
                self,
                None if a_ns == _NO_ENTRY else self._string(a_ns),
                self._string(a_name),
                resid,
                -1 if a_raw == _NO_ENTRY else a_raw,
                a_type, a_data, a_off))
        depth = len(stack)
        path = tuple(stack) + (name,)
        return Element(name, ns, depth, path, attrs, off, line), line

    def _string(self, idx: int) -> str:
        if not 0 <= idx < len(self.strings):
            raise AxmlError(f"string index {idx} out of range (pool has {len(self.strings)})")
        return self.strings[idx]

    # -- access ------------------------------------------------------------------------

    def elements(self) -> list:
        """Every element start node, in document order."""
        return self._elements

    def find(self, path: tuple, attr_filter: Optional[dict] = None) -> list:
        """Elements whose ``path`` equals ``path`` and that match every ``attr_filter``
        entry ``{attr_name: expected_value}`` (attributes matched by local name)."""
        want = tuple(path)
        out = []
        for el in self._elements:
            if el.path != want:
                continue
            if attr_filter:
                ok = True
                for aname, expected in attr_filter.items():
                    a = self._attr_local(el, aname)
                    if a is None or a.value != expected:
                        ok = False
                        break
                if not ok:
                    continue
            out.append(el)
        return out

    @staticmethod
    def _attr_local(element: Element, name: str) -> Optional[Attr]:
        for a in element.attrs:
            if a.name == name:
                return a
        return None

    def attr(self, element: Element, name: str, ns: Optional[str] = ANDROID_NS) -> Optional[Attr]:
        """The attribute of ``element`` with local name ``name`` and namespace ``ns``."""
        for a in element.attrs:
            if a.name == name and a.ns == ns:
                return a
        return None

    def find_string(self, s: str) -> Optional[int]:
        """The pool index of ``s``, or None."""
        try:
            return self.strings.index(s)
        except ValueError:
            return None

    # -- in-place editing --------------------------------------------------------------

    def set_attr_string(self, attr: Attr, string_idx: int) -> None:
        """Point ``attr`` at pool string ``string_idx`` (TYPE_STRING), in place."""
        if not 0 <= string_idx < len(self.strings):
            raise AxmlError(f"string index {string_idx} out of range")
        off = attr.offset
        struct.pack_into("<I", self._data, off + 8, string_idx)   # rawValue
        self._data[off + 15] = TYPE_STRING                        # Res_value.dataType
        struct.pack_into("<I", self._data, off + 16, string_idx)  # Res_value.data
        attr.raw_value_idx = string_idx
        attr.type = TYPE_STRING
        attr.data = string_idx

    def set_attr_typed(self, attr: Attr, data_type: int, data: int, keep_raw: bool = False) -> None:
        """Overwrite ``attr``'s ``Res_value`` type and data in place; clear rawValue unless
        ``keep_raw``."""
        off = attr.offset
        self._data[off + 15] = data_type & 0xFF
        struct.pack_into("<I", self._data, off + 16, data & 0xFFFFFFFF)
        attr.type = data_type
        attr.data = data & 0xFFFFFFFF
        if not keep_raw:
            struct.pack_into("<I", self._data, off + 8, _NO_ENTRY)
            attr.raw_value_idx = -1

    # -- output ------------------------------------------------------------------------

    def to_bytes(self) -> bytes:
        """The current bytes; always the same length as the parsed input."""
        out = bytes(self._data)
        assert len(out) == self._orig_len, "axml edit changed the file length"
        return out

    def _subtree_range(self, el: "Element") -> tuple:
        """The byte range [start, end) of ``el``'s START chunk through its matching END chunk,
        including any nested children. ``el.offset`` must be a START_ELEMENT chunk."""
        buf = self._data
        top_size = _u32(buf, 4)
        off = el.offset
        depth = 0
        while off + _CHUNK_HEADER.size <= top_size:
            ctype, _chdr, csize = _CHUNK_HEADER.unpack_from(buf, off)
            if csize < _CHUNK_HEADER.size:
                raise AxmlError("bad chunk size while scanning a subtree")
            if ctype == _RES_XML_START_ELEMENT:
                depth += 1
            elif ctype == _RES_XML_END_ELEMENT:
                depth -= 1
                if depth == 0:
                    return el.offset, off + csize
            off += csize
        raise AxmlError("no matching element end found")

    def remove_elements(self, predicate) -> bytes:
        """Remove every top-level element for which ``predicate(element)`` is true, with its whole
        subtree, and return the new (shorter) bytes. The top RES_XML chunk size is reduced to match;
        the string pool and resource map are left as they are (unused strings simply remain).

        This re-parses internally and does not mutate this instance.
        """
        ranges = []
        for el in self._elements:
            if predicate(el):
                ranges.append(self._subtree_range(el))
        if not ranges:
            return bytes(self._data)
        # splice out from the end so earlier offsets stay valid; refuse overlaps.
        ranges.sort(key=lambda r: r[0], reverse=True)
        out = bytearray(self._data)
        removed = 0
        last_start = len(out) + 1
        for start, end in ranges:
            if end > last_start:
                raise AxmlError("overlapping removal ranges")
            last_start = start
            del out[start:end]
            removed += end - start
        # shrink the top chunk's total size
        new_top = _u32(out, 4) - removed
        struct.pack_into("<I", out, 4, new_top)
        if new_top != len(out):
            raise AxmlError(f"size bookkeeping mismatch ({new_top} != {len(out)})")
        return bytes(out)

    def to_xml(self) -> str:
        """A deterministic, indented, one-element-per-line rendering used for diffs."""
        lines = []
        for el in self._elements:
            indent = "  " * el.depth
            parts = ["<" + el.name]
            for a in el.attrs:
                if a.ns == ANDROID_NS:
                    aname = "android:" + a.name
                else:
                    aname = a.name
                parts.append(f'{aname}="{_value_str(a)}"')
            lines.append(indent + " ".join(parts) + ">")
        return "\n".join(lines)

    def __repr__(self) -> str:
        return (f"AxmlDoc(strings={len(self.strings)}, utf8={self.utf8}, "
                f"elements={len(self._elements)})")


def _read_len16(buf, pos, limit):
    if pos + 2 > limit:
        raise AxmlError("truncated UTF-16 length")
    v = struct.unpack_from("<H", buf, pos)[0]
    pos += 2
    if v & 0x8000:
        if pos + 2 > limit:
            raise AxmlError("truncated UTF-16 extended length")
        v = ((v & 0x7FFF) << 16) | struct.unpack_from("<H", buf, pos)[0]
        pos += 2
    return v, pos


def _read_len8(buf, pos, limit):
    if pos + 1 > limit:
        raise AxmlError("truncated UTF-8 length")
    v = buf[pos]
    pos += 1
    if v & 0x80:
        if pos + 1 > limit:
            raise AxmlError("truncated UTF-8 extended length")
        v = ((v & 0x7F) << 8) | buf[pos]
        pos += 1
    return v, pos


def _value_str(attr: Attr) -> str:
    v = attr.value
    if isinstance(v, bool):
        return "true" if v else "false"
    return str(v)


# ---------------------------------------------------------------------------------------
# Manifest-level helpers
# ---------------------------------------------------------------------------------------


def manifest_info(data: bytes) -> dict:
    """Pull the fields the patcher cares about out of a binary ``AndroidManifest.xml``.

    Returns a dict with keys ``package``, ``versionCode``, ``versionName``, ``split``,
    ``requiredSplitTypes``, ``splitTypes``, ``application_name``, ``minSdkVersion``,
    ``targetSdkVersion`` and ``extractNativeLibs``. A field that is absent is ``None``.
    """
    doc = AxmlDoc.parse(data)
    info = {
        "package": None,
        "versionCode": None,
        "versionName": None,
        "split": None,
        "requiredSplitTypes": None,
        "splitTypes": None,
        "application_name": None,
        "minSdkVersion": None,
        "targetSdkVersion": None,
        "extractNativeLibs": None,
    }

    def val(attr):
        return None if attr is None else attr.value

    mans = doc.find(("manifest",))
    if mans:
        m = mans[0]
        info["package"] = val(doc.attr(m, "package", ns=None))
        info["versionCode"] = val(doc.attr(m, "versionCode"))
        info["versionName"] = val(doc.attr(m, "versionName"))
        info["split"] = val(doc.attr(m, "split", ns=None))
        info["requiredSplitTypes"] = val(doc.attr(m, "requiredSplitTypes"))
        info["splitTypes"] = val(doc.attr(m, "splitTypes"))

    sdks = doc.find(("manifest", "uses-sdk"))
    if sdks:
        s = sdks[0]
        info["minSdkVersion"] = val(doc.attr(s, "minSdkVersion"))
        info["targetSdkVersion"] = val(doc.attr(s, "targetSdkVersion"))

    apps = doc.find(("manifest", "application"))
    if apps:
        a = apps[0]
        info["application_name"] = val(doc.attr(a, "name"))
        info["extractNativeLibs"] = val(doc.attr(a, "extractNativeLibs"))

    return info


def diff_xml(a: "AxmlDoc", b: "AxmlDoc") -> list:
    """A unified diff of two documents' :meth:`AxmlDoc.to_xml` renderings, as lines."""
    return list(difflib.unified_diff(
        a.to_xml().splitlines(), b.to_xml().splitlines(),
        fromfile="a", tofile="b", lineterm=""))
