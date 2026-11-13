"""A tiny synthetic Android binary-XML encoder, used only by the tests.

It builds a well-formed ``AndroidManifest.xml`` chunk stream (string pool, resource map,
namespace + element nodes) in either a UTF-8 or a UTF-16 string pool, so the parser in
``sgmo_patcher.axml`` can be exercised without any real game file. It is deliberately small
and only emits what the tests need; it is not a general compiler.

Value specs passed to :meth:`AxmlBuilder.start` as ``(ns, name, spec)``:

* ``("str", text)``  -> TYPE_STRING
* ``("int", n)``     -> TYPE_INT_DEC
* ``("bool", flag)`` -> TYPE_INT_BOOLEAN
* ``("ref", n)``     -> TYPE_REFERENCE
"""

from __future__ import annotations

import struct

ANDROID_NS = "http://schemas.android.com/apk/res/android"

_NO_ENTRY = 0xFFFFFFFF

TYPE_REFERENCE = 0x01
TYPE_STRING = 0x03
TYPE_INT_DEC = 0x10
TYPE_INT_BOOLEAN = 0x12


def _len16(n):
    if n < 0x8000:
        return struct.pack("<H", n)
    return struct.pack("<HH", (n >> 16) | 0x8000, n & 0xFFFF)


def _len8(n):
    if n < 0x80:
        return bytes([n])
    return bytes([(n >> 8) | 0x80, n & 0xFF])


def _pad4(b: bytearray) -> None:
    while len(b) % 4:
        b.append(0)


class AxmlBuilder:
    def __init__(self, utf8: bool = False):
        self.utf8 = utf8
        self._strings: list[str] = []
        self._resids: dict[str, int] = {}
        self._events: list = []
        self._line = 1

    def string(self, s: str, resid: int | None = None) -> str:
        """Intern ``s`` in the pool; if ``resid`` is given, mark it for the resource map."""
        if s not in self._strings:
            self._strings.append(s)
        if resid is not None:
            self._resids[s] = resid
        return s

    def start_namespace(self, prefix: str, uri: str) -> None:
        self.string(prefix)
        self.string(uri)
        self._events.append(("ns_start", prefix, uri))

    def end_namespace(self, prefix: str, uri: str) -> None:
        self._events.append(("ns_end", prefix, uri))

    def start(self, name: str, ns: str | None = None, attrs=()) -> None:
        self.string(name)
        if ns is not None:
            self.string(ns)
        norm = []
        for a_ns, a_name, spec in attrs:
            if a_ns is not None:
                self.string(a_ns)
            self.string(a_name)
            kind = spec[0]
            if kind == "str":
                self.string(spec[1])
            norm.append((a_ns, a_name, spec))
        self._events.append(("start", name, ns, norm))

    def end(self, name: str, ns: str | None = None) -> None:
        self._events.append(("end", name, ns))

    # -- building ----------------------------------------------------------------------

    def _ordered_pool(self):
        """Mapped strings (those with resource ids) first, so the resource map is parallel to
        the first N pool strings, then the rest in insertion order."""
        mapped = [s for s in self._strings if s in self._resids]
        rest = [s for s in self._strings if s not in self._resids]
        pool = mapped + rest
        index = {s: i for i, s in enumerate(pool)}
        res_ids = [self._resids[s] for s in mapped]
        return pool, index, res_ids

    def build(self) -> bytes:
        pool, index, res_ids = self._ordered_pool()

        def sref(s):
            return index[s] if s is not None else _NO_ENTRY

        string_pool = self._build_string_pool(pool)
        res_map = self._build_resource_map(res_ids)
        nodes = bytearray()
        for ev in self._events:
            if ev[0] == "ns_start":
                nodes += self._node_namespace(0x0100, sref(ev[1]), sref(ev[2]))
            elif ev[0] == "ns_end":
                nodes += self._node_namespace(0x0101, sref(ev[1]), sref(ev[2]))
            elif ev[0] == "start":
                nodes += self._node_start(ev[1], ev[2], ev[3], index, sref)
            elif ev[0] == "end":
                nodes += self._node_end(ev[1], ev[2], sref)

        inner = string_pool + res_map + nodes
        out = bytearray()
        out += struct.pack("<HHI", 0x0003, 8, 8 + len(inner))
        out += inner
        return bytes(out)

    def _build_string_pool(self, pool) -> bytes:
        string_count = len(pool)
        flags = 0x0100 if self.utf8 else 0
        data = bytearray()
        offsets = []
        for s in pool:
            offsets.append(len(data))
            if self.utf8:
                b = s.encode("utf-8")
                units = len(s.encode("utf-16-le")) // 2
                data += _len8(units)
                data += _len8(len(b))
                data += b
                data.append(0)
            else:
                u = s.encode("utf-16-le")
                data += _len16(len(u) // 2)
                data += u
                data += b"\x00\x00"
        _pad4(data)
        strings_start = 28 + 4 * string_count
        body = bytearray()
        body += struct.pack("<IIIII", string_count, 0, flags, strings_start, 0)
        for off in offsets:
            body += struct.pack("<I", off)
        body += data
        size = 8 + len(body)
        return struct.pack("<HHI", 0x0001, 28, size) + bytes(body)

    def _build_resource_map(self, res_ids) -> bytes:
        if not res_ids:
            return b""
        body = b"".join(struct.pack("<I", r) for r in res_ids)
        size = 8 + len(body)
        return struct.pack("<HHI", 0x0180, 8, size) + body

    def _next_line(self):
        line = self._line
        self._line += 1
        return line

    def _node_namespace(self, ctype, prefix_ref, uri_ref) -> bytes:
        size = 24
        return (struct.pack("<HHI", ctype, 16, size)
                + struct.pack("<II", self._next_line(), _NO_ENTRY)
                + struct.pack("<II", prefix_ref, uri_ref))

    def _attr_bytes(self, a_ns, a_name, spec, index, sref) -> bytes:
        ns_ref = sref(a_ns)
        name_ref = index[a_name]
        kind = spec[0]
        if kind == "str":
            idx = index[spec[1]]
            raw = idx
            dtype = TYPE_STRING
            data = idx
        elif kind == "int":
            raw = _NO_ENTRY
            dtype = TYPE_INT_DEC
            data = spec[1] & 0xFFFFFFFF
        elif kind == "bool":
            raw = _NO_ENTRY
            dtype = TYPE_INT_BOOLEAN
            data = 0xFFFFFFFF if spec[1] else 0
        elif kind == "ref":
            raw = _NO_ENTRY
            dtype = TYPE_REFERENCE
            data = spec[1] & 0xFFFFFFFF
        else:
            raise ValueError(f"unknown value spec {spec!r}")
        return (struct.pack("<III", ns_ref, name_ref, raw)
                + struct.pack("<HBBI", 8, 0, dtype, data))

    def _node_start(self, name, ns, attrs, index, sref) -> bytes:
        attr_blob = bytearray()
        for a_ns, a_name, spec in attrs:
            attr_blob += self._attr_bytes(a_ns, a_name, spec, index, sref)
        body = bytearray()
        body += struct.pack("<II", self._next_line(), _NO_ENTRY)   # line, comment
        body += struct.pack("<II", sref(ns), index[name])          # ns, name
        body += struct.pack("<HHH", 20, 20, len(attrs))            # attrStart, attrSize, count
        body += struct.pack("<HHH", 0, 0, 0)                       # id/class/style index
        body += attr_blob
        size = 8 + len(body)
        return struct.pack("<HHI", 0x0102, 16, size) + bytes(body)

    def _node_end(self, name, ns, sref) -> bytes:
        size = 24
        return (struct.pack("<HHI", 0x0103, 16, size)
                + struct.pack("<II", self._next_line(), _NO_ENTRY)
                + struct.pack("<II", sref(ns), sref(name)))


def build_sample_manifest(utf8: bool = False) -> bytes:
    """A manifest exercising the fields the tests check, in a UTF-8 or UTF-16 pool."""
    b = AxmlBuilder(utf8=utf8)
    # android attribute names carry resource ids (parallel to the first pool strings).
    b.string("versionCode", 0x0101021B)
    b.string("versionName", 0x0101021C)
    b.string("requiredSplitTypes", 0x0101064E)
    b.string("splitTypes", 0x0101064F)
    b.string("minSdkVersion", 0x0101020C)
    b.string("targetSdkVersion", 0x01010270)
    b.string("name", 0x01010003)
    b.string("value", 0x01010024)
    b.string("extractNativeLibs", 0x010104EA)
    # An empty string must exist in the pool (set_attr_string targets it later).
    b.string("")

    b.start_namespace("android", ANDROID_NS)
    b.start("manifest", None, [
        (None, "package", ("str", "com.example.app")),
        (ANDROID_NS, "versionCode", ("int", 266)),
        (ANDROID_NS, "versionName", ("str", "8.12.0")),
        (ANDROID_NS, "requiredSplitTypes", ("str", "base__abi")),
        (ANDROID_NS, "splitTypes", ("str", "")),
    ])
    b.start("uses-sdk", None, [
        (ANDROID_NS, "minSdkVersion", ("int", 24)),
        (ANDROID_NS, "targetSdkVersion", ("int", 33)),
    ])
    b.end("uses-sdk")
    b.start("application", None, [
        (ANDROID_NS, "name", ("str", "com.pairip.application.Application")),
        (ANDROID_NS, "extractNativeLibs", ("bool", False)),
    ])
    b.start("meta-data", None, [
        (ANDROID_NS, "name", ("str", "com.android.vending.splits.required")),
        (ANDROID_NS, "value", ("bool", True)),
    ])
    b.end("meta-data")
    b.end("application")
    b.end("manifest")
    b.end_namespace("android", ANDROID_NS)
    return b.build()
