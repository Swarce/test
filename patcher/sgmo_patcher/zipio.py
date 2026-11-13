"""Raw ZIP reading and aligned, deterministic ZIP writing for APKs.

APKs are large (the game's base APK is about 130 MB with about 5800 entries), and nearly
every entry is copied unchanged into the patched APK. This module therefore works on the
*compressed* bytes: entries are copied from the source file to the output file in chunks,
never decompressed and recompressed, and never loaded into memory as a whole.

Reading (:class:`ZipIn`)
    Parses the End of Central Directory record (ZIP64 included; spanned/multi-disk archives
    are refused), the central directory, and each entry's local header on first use (to find
    where its data starts). Duplicate names are refused: an APK with two entries of the same
    name is ambiguous (different tools would pick different ones) and is a tampering red flag.

Planning (:class:`ZipPlan`)
    An ordered map of output entry name -> source: :class:`RawCopy` (an entry of an input
    zip, copied byte for byte) or :class:`NewData` (bytes to store or deflate).

Writing (:func:`write`)
    Writes local headers, data, the central directory and the EOCD. No data descriptors (the
    real CRC and sizes are in each local header). STORED entries are aligned the way
    ``zipalign -P 16 4`` does it: a padding extra field in the local header makes the data
    start on a 4-byte boundary, or a 16 KiB boundary for names ending in ``.so``. Output is
    deterministic: the same plan always produces the same bytes (for new deflated data, on
    the same zlib build).

Only the features APKs use are supported: methods 0 (stored) and 8 (deflate), no
encryption, no ZIP64 on output.
"""

from __future__ import annotations

import os
import struct
import zlib
from dataclasses import dataclass
from typing import BinaryIO, Iterator, Optional, Union

__all__ = [
    "ZipError",
    "Entry",
    "ZipIn",
    "RawStream",
    "RawCopy",
    "NewData",
    "ZipPlan",
    "write",
    "verify_alignment",
    "METHOD_STORED",
    "METHOD_DEFLATE",
    "FIXED_DOS_TIME",
    "FIXED_DOS_DATE",
]

METHOD_STORED = 0
METHOD_DEFLATE = 8
_METHOD_BY_NAME = {"stored": METHOD_STORED, "deflate": METHOD_DEFLATE}
_NAME_BY_METHOD = {v: k for k, v in _METHOD_BY_NAME.items()}

# Fixed timestamp for entries we create: 1981-01-01 01:01:02 (the value Android's own
# build tools use), so the output does not depend on when the patcher ran.
FIXED_DOS_DATE = ((1981 - 1980) << 9) | (1 << 5) | 1
FIXED_DOS_TIME = (1 << 11) | (1 << 5) | (2 // 2)

_SIG_LOCAL = 0x04034B50
_SIG_CENTRAL = 0x02014B50
_SIG_EOCD = 0x06054B50
_SIG_ZIP64_EOCD = 0x06064B50
_SIG_ZIP64_LOCATOR = 0x07064B50

_LOCAL = struct.Struct("<IHHHHHIIIHH")  # 30 bytes
_CENTRAL = struct.Struct("<IHHHHHHIIIHHHHHII")  # 46 bytes
_EOCD = struct.Struct("<IHHHHIIH")  # 22 bytes
_ZIP64_EOCD = struct.Struct("<IQHHIIQQQQ")  # 56 bytes
_ZIP64_LOCATOR = struct.Struct("<IIQI")  # 20 bytes

_FLAG_ENCRYPTED = 0x0001
_FLAG_DATA_DESCRIPTOR = 0x0008
_FLAG_UTF8 = 0x0800

# Extra field used by Android's zipalign/apksigner for alignment padding:
# id 0xD935, data = uint16 alignment + zero padding. Readers ignore it.
_ALIGN_EXTRA_ID = 0xD935
_ZIP64_EXTRA_ID = 0x0001

_CHUNK = 1 << 20
_MAX_EOCD_SEARCH = _EOCD.size + 0xFFFF


class ZipError(Exception):
    """The zip is malformed, uses a feature APKs do not use, or an operation is invalid."""


def _decode_name(raw: bytes, flags: int) -> str:
    """Decode an entry name.

    With the UTF-8 flag the name is UTF-8. Without it, the name is decoded as UTF-8 when it
    is valid UTF-8 (Android treats every name as UTF-8) and as CP437 otherwise (the ZIP
    specification's default). ASCII names decode the same either way.
    """
    if flags & _FLAG_UTF8:
        try:
            return raw.decode("utf-8")
        except UnicodeDecodeError as exc:
            raise ZipError(f"entry name {raw!r} is flagged UTF-8 but is not valid UTF-8") from exc
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("cp437")


class Entry:
    """One central-directory entry of a :class:`ZipIn`.

    Attributes mirror the central directory record. ``data_offset`` (where the compressed
    data starts) is read from the entry's *local* header on first access, because the local
    name/extra lengths may differ from the central ones (alignment padding lives there).
    """

    __slots__ = (
        "name",
        "name_bytes",
        "method",
        "crc32",
        "compressed_size",
        "size",
        "header_offset",
        "flags",
        "external_attr",
        "internal_attr",
        "create_version",
        "extract_version",
        "dos_time",
        "dos_date",
        "_data_offset",
        "_owner",
    )

    def __init__(self, owner: "ZipIn", **fields):
        self._owner = owner
        self._data_offset: Optional[int] = None
        for key, value in fields.items():
            setattr(self, key, value)

    @property
    def data_offset(self) -> int:
        """Absolute file offset of the entry's compressed data (from the local header)."""
        if self._data_offset is None:
            self._data_offset = self._owner._resolve_data_offset(self)
        return self._data_offset

    @property
    def is_dir(self) -> bool:
        """True for directory entries (name ends with '/')."""
        return self.name.endswith("/")

    @property
    def method_name(self) -> str:
        """'stored', 'deflate', or 'method-N' for anything else."""
        return _NAME_BY_METHOD.get(self.method, f"method-{self.method}")

    def __repr__(self) -> str:
        return (
            f"Entry({self.name!r}, method={self.method}, size={self.size}, "
            f"compressed_size={self.compressed_size}, header_offset={self.header_offset})"
        )


class RawStream:
    """Read-only, seekable file-like view of one entry's compressed bytes.

    It shares the :class:`ZipIn`'s file handle and seeks before every read, so it is cheap
    but not thread-safe.
    """

    def __init__(self, f: BinaryIO, start: int, length: int):
        self._f = f
        self._start = start
        self._length = length
        self._pos = 0

    def read(self, n: int = -1) -> bytes:
        """Read up to ``n`` bytes (all remaining bytes when ``n`` < 0)."""
        remaining = self._length - self._pos
        if n is None or n < 0 or n > remaining:
            n = remaining
        if n <= 0:
            return b""
        self._f.seek(self._start + self._pos)
        data = self._f.read(n)
        if len(data) != n:
            raise ZipError("unexpected end of file while reading entry data")
        self._pos += n
        return data

    def seek(self, offset: int, whence: int = os.SEEK_SET) -> int:
        """Move within the entry's data; returns the new position."""
        if whence == os.SEEK_SET:
            pos = offset
        elif whence == os.SEEK_CUR:
            pos = self._pos + offset
        elif whence == os.SEEK_END:
            pos = self._length + offset
        else:
            raise ValueError(f"invalid whence {whence}")
        if pos < 0:
            raise ValueError("negative seek position")
        self._pos = pos
        return pos

    def tell(self) -> int:
        """Current position within the entry's data."""
        return self._pos

    def readable(self) -> bool:
        return True

    def seekable(self) -> bool:
        return True

    def close(self) -> None:
        """No-op: the underlying file belongs to the ZipIn."""

    def __enter__(self) -> "RawStream":
        return self

    def __exit__(self, *exc) -> None:
        self.close()


class ZipIn:
    """A zip file opened for raw reading.

    ``ZipIn(path)`` parses the central directory immediately; local headers are read on
    demand. Use as a context manager or call :meth:`close`.
    """

    def __init__(self, path: Union[str, os.PathLike]):
        self.path = os.fspath(path)
        self._f: Optional[BinaryIO] = open(self.path, "rb")
        try:
            self._file_size = os.fstat(self._f.fileno()).st_size
            self._parse()
        except BaseException:
            self._f.close()
            self._f = None
            raise

    # -- parsing -------------------------------------------------------------------------

    def _pread(self, offset: int, length: int) -> bytes:
        f = self._file()
        f.seek(offset)
        data = f.read(length)
        if len(data) != length:
            raise ZipError(f"{self.path}: unexpected end of file at offset {offset}")
        return data

    def _find_eocd(self) -> tuple[int, tuple]:
        """Locate the EOCD record: the last signature whose comment ends exactly at EOF."""
        if self._file_size < _EOCD.size:
            raise ZipError(f"{self.path}: too small to be a zip file")
        tail_len = min(self._file_size, _MAX_EOCD_SEARCH)
        tail_start = self._file_size - tail_len
        tail = self._pread(tail_start, tail_len)
        sig = struct.pack("<I", _SIG_EOCD)
        pos = tail.rfind(sig)
        found_signature = False
        while pos >= 0:
            if pos + _EOCD.size <= len(tail):
                found_signature = True
                rec = _EOCD.unpack_from(tail, pos)
                if pos + _EOCD.size + rec[7] == len(tail):
                    return tail_start + pos, rec
            pos = tail.rfind(sig, 0, pos)
        if found_signature:
            raise ZipError(f"{self.path}: the end-of-central-directory record is inconsistent "
                           "(truncated file or trailing data)")
        raise ZipError(f"{self.path}: not a zip file (no end-of-central-directory record)")

    def _parse(self) -> None:
        eocd_pos, rec = self._find_eocd()
        _, disk, cd_disk, n_disk, n_total, cd_size, cd_offset, comment_len = rec
        self.comment = self._pread(eocd_pos + _EOCD.size, comment_len) if comment_len else b""
        cd_end_limit = eocd_pos
        self.zip64 = False

        needs64 = (n_total == 0xFFFF or n_disk == 0xFFFF or cd_size == 0xFFFFFFFF
                   or cd_offset == 0xFFFFFFFF or disk == 0xFFFF or cd_disk == 0xFFFF)
        loc_pos = eocd_pos - _ZIP64_LOCATOR.size
        has_locator = (loc_pos >= 0 and
                       struct.unpack("<I", self._pread(loc_pos, 4))[0] == _SIG_ZIP64_LOCATOR)
        if has_locator:
            _, loc_disk, z64_pos, n_disks = _ZIP64_LOCATOR.unpack(
                self._pread(loc_pos, _ZIP64_LOCATOR.size))
            if loc_disk != 0 or n_disks > 1:
                raise ZipError(f"{self.path}: multi-disk (spanned) zip archives are not supported")
            if z64_pos + _ZIP64_EOCD.size > loc_pos:
                raise ZipError(f"{self.path}: ZIP64 end-of-central-directory record is out of range")
            z = _ZIP64_EOCD.unpack(self._pread(z64_pos, _ZIP64_EOCD.size))
            if z[0] != _SIG_ZIP64_EOCD:
                raise ZipError(f"{self.path}: ZIP64 locator points to an invalid record")
            _, _, _, _, disk, cd_disk, n_disk, n_total, cd_size, cd_offset = z
            cd_end_limit = z64_pos
            self.zip64 = True
        elif needs64:
            raise ZipError(f"{self.path}: ZIP64 values in the EOCD but no ZIP64 locator")

        if disk != 0 or cd_disk != 0 or n_disk != n_total:
            raise ZipError(f"{self.path}: multi-disk (spanned) zip archives are not supported")
        if cd_offset + cd_size > cd_end_limit:
            raise ZipError(f"{self.path}: central directory lies outside the file")
        self.cd_offset = cd_offset
        self.cd_size = cd_size

        cd = self._pread(cd_offset, cd_size)
        entries: list[Entry] = []
        by_name: dict[str, Entry] = {}
        pos = 0
        for index in range(n_total):
            if pos + _CENTRAL.size > len(cd):
                raise ZipError(f"{self.path}: central directory is truncated at entry {index}")
            (sig, create_version, extract_version, flags, method, dos_time, dos_date, crc,
             csize, usize, nlen, elen, clen, disk_start, internal_attr, external_attr,
             header_offset) = _CENTRAL.unpack_from(cd, pos)
            if sig != _SIG_CENTRAL:
                raise ZipError(f"{self.path}: bad central directory signature at entry {index}")
            p = pos + _CENTRAL.size
            if p + nlen + elen + clen > len(cd):
                raise ZipError(f"{self.path}: central directory is truncated at entry {index}")
            name_bytes = cd[p:p + nlen]
            extra = cd[p + nlen:p + nlen + elen]
            pos = p + nlen + elen + clen

            if 0xFFFFFFFF in (usize, csize, header_offset) or disk_start == 0xFFFF:
                usize, csize, header_offset, disk_start = self._apply_zip64_extra(
                    extra, usize, csize, header_offset, disk_start, name_bytes)
            if disk_start != 0:
                raise ZipError(f"{self.path}: multi-disk (spanned) zip archives are not supported")
            name = _decode_name(name_bytes, flags)
            if name in by_name:
                raise ZipError(
                    f"{self.path}: duplicate entry name {name!r} in the central directory. "
                    "An APK with duplicate entries is ambiguous and may have been tampered with.")
            if header_offset + _LOCAL.size > cd_offset:
                raise ZipError(f"{self.path}: entry {name!r} has a local header outside the data area")
            entry = Entry(
                self, name=name, name_bytes=name_bytes, method=method, crc32=crc,
                compressed_size=csize, size=usize, header_offset=header_offset, flags=flags,
                external_attr=external_attr, internal_attr=internal_attr,
                create_version=create_version, extract_version=extract_version,
                dos_time=dos_time, dos_date=dos_date)
            entries.append(entry)
            by_name[name] = entry
        if pos != len(cd):
            raise ZipError(f"{self.path}: central directory size does not match its entries")
        self.entries: list[Entry] = entries
        self._by_name = by_name

    def _apply_zip64_extra(self, extra, usize, csize, header_offset, disk_start, name_bytes):
        """Replace 0xFFFFFFFF placeholders with the values from the ZIP64 extra field."""
        pos = 0
        while pos + 4 <= len(extra):
            hid, hlen = struct.unpack_from("<HH", extra, pos)
            body = extra[pos + 4:pos + 4 + hlen]
            pos += 4 + hlen
            if hid != _ZIP64_EXTRA_ID:
                continue
            q = 0
            try:
                if usize == 0xFFFFFFFF:
                    usize = struct.unpack_from("<Q", body, q)[0]
                    q += 8
                if csize == 0xFFFFFFFF:
                    csize = struct.unpack_from("<Q", body, q)[0]
                    q += 8
                if header_offset == 0xFFFFFFFF:
                    header_offset = struct.unpack_from("<Q", body, q)[0]
                    q += 8
                if disk_start == 0xFFFF:
                    disk_start = struct.unpack_from("<I", body, q)[0]
            except struct.error as exc:
                raise ZipError(f"{self.path}: ZIP64 extra field of {name_bytes!r} is too short") from exc
            return usize, csize, header_offset, disk_start
        raise ZipError(f"{self.path}: entry {name_bytes!r} needs a ZIP64 extra field but has none")

    def _resolve_data_offset(self, entry: Entry) -> int:
        """Read the entry's local header and return where its data starts."""
        hdr = _LOCAL.unpack(self._pread(entry.header_offset, _LOCAL.size))
        if hdr[0] != _SIG_LOCAL:
            raise ZipError(f"{self.path}: bad local header signature for {entry.name!r}")
        nlen, elen = hdr[9], hdr[10]
        local_name = self._pread(entry.header_offset + _LOCAL.size, nlen)
        if local_name != entry.name_bytes:
            raise ZipError(f"{self.path}: local header name {local_name!r} does not match the "
                           f"central directory name {entry.name_bytes!r}")
        data_offset = entry.header_offset + _LOCAL.size + nlen + elen
        if data_offset + entry.compressed_size > self.cd_offset:
            raise ZipError(f"{self.path}: data of {entry.name!r} runs into the central directory")
        return data_offset

    # -- access --------------------------------------------------------------------------

    def _file(self) -> BinaryIO:
        if self._f is None:
            raise ZipError(f"{self.path}: zip file is closed")
        return self._f

    def _entry(self, item: Union[str, Entry]) -> Entry:
        if isinstance(item, Entry):
            if item._owner is not self:
                raise ZipError(f"entry {item.name!r} belongs to a different zip file")
            return item
        try:
            return self._by_name[item]
        except KeyError:
            raise KeyError(f"{self.path}: no entry named {item!r}") from None

    def names(self) -> list[str]:
        """Entry names in central-directory order."""
        return [e.name for e in self.entries]

    def get(self, name: str) -> Optional[Entry]:
        """The entry called ``name``, or None."""
        return self._by_name.get(name)

    def __contains__(self, name: object) -> bool:
        return name in self._by_name

    def __len__(self) -> int:
        return len(self.entries)

    def open_raw(self, item: Union[str, Entry]) -> RawStream:
        """A file-like view of the entry's compressed bytes (as stored in the zip)."""
        e = self._entry(item)
        return RawStream(self._file(), e.data_offset, e.compressed_size)

    def read_raw(self, item: Union[str, Entry]) -> bytes:
        """The entry's compressed bytes."""
        with self.open_raw(item) as s:
            return s.read()

    def iter_raw(self, item: Union[str, Entry], chunk_size: int = _CHUNK) -> Iterator[bytes]:
        """Yield the entry's compressed bytes in chunks of at most ``chunk_size``."""
        s = self.open_raw(item)
        while True:
            chunk = s.read(chunk_size)
            if not chunk:
                return
            yield chunk

    def iter_data(self, item: Union[str, Entry], chunk_size: int = _CHUNK) -> Iterator[bytes]:
        """Yield the entry's uncompressed bytes in chunks; the CRC and size are checked at the end.

        Raises :class:`ZipError` for encrypted entries, methods other than stored/deflate,
        corrupt deflate data, or a CRC/size mismatch.
        """
        e = self._entry(item)
        if e.flags & _FLAG_ENCRYPTED:
            raise ZipError(f"{self.path}: entry {e.name!r} is encrypted")
        if e.method not in (METHOD_STORED, METHOD_DEFLATE):
            raise ZipError(f"{self.path}: entry {e.name!r} uses compression method {e.method}; "
                           "only stored (0) and deflate (8) are supported")
        if e.method == METHOD_STORED and e.compressed_size != e.size:
            raise ZipError(f"{self.path}: stored entry {e.name!r} has different compressed and "
                           "uncompressed sizes")
        crc = 0
        total = 0
        d = zlib.decompressobj(-15) if e.method == METHOD_DEFLATE else None
        try:
            for raw in self.iter_raw(e, chunk_size):
                out = d.decompress(raw) if d else raw
                if out:
                    crc = zlib.crc32(out, crc)
                    total += len(out)
                    yield out
            if d:
                tail = d.flush()
                if tail:
                    crc = zlib.crc32(tail, crc)
                    total += len(tail)
                    yield tail
                if not d.eof:
                    raise ZipError(f"{self.path}: deflate data of {e.name!r} is truncated")
        except zlib.error as exc:
            raise ZipError(f"{self.path}: deflate data of {e.name!r} is corrupt: {exc}") from exc
        if total != e.size:
            raise ZipError(f"{self.path}: {e.name!r} decompressed to {total} bytes, "
                           f"expected {e.size}")
        if crc != e.crc32:
            raise ZipError(f"{self.path}: CRC mismatch for {e.name!r} "
                           f"(got {crc:08x}, expected {e.crc32:08x})")

    def read(self, item: Union[str, Entry]) -> bytes:
        """The entry's uncompressed bytes, CRC-checked."""
        return b"".join(self.iter_data(item))

    def close(self) -> None:
        """Close the underlying file (idempotent)."""
        if self._f is not None:
            self._f.close()
            self._f = None

    @property
    def closed(self) -> bool:
        return self._f is None

    def __enter__(self) -> "ZipIn":
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def __repr__(self) -> str:
        return f"ZipIn({self.path!r}, entries={len(self.entries)})"


# ---------------------------------------------------------------------------------------
# Plan
# ---------------------------------------------------------------------------------------


@dataclass(frozen=True)
class RawCopy:
    """Copy ``entry`` of ``zipin`` byte for byte (compressed data, method, CRC, timestamps)."""

    zipin: ZipIn
    entry: Entry

    @property
    def method(self) -> str:
        return _NAME_BY_METHOD[self.entry.method]


class NewData:
    """New entry content: ``data`` written with ``method`` ('stored' or 'deflate').

    ``dos_time``/``dos_date`` default to the fixed timestamp; ``external_attr`` defaults to 0.
    Deflate output is computed once, on first use (zlib level 9, raw deflate).
    """

    __slots__ = ("data", "method", "dos_time", "dos_date", "external_attr", "crc32", "_compressed")

    def __init__(self, data: bytes, method: str = "deflate", *, dos_time: int = FIXED_DOS_TIME,
                 dos_date: int = FIXED_DOS_DATE, external_attr: int = 0):
        if method not in _METHOD_BY_NAME:
            raise ValueError(f"method must be 'stored' or 'deflate', not {method!r}")
        self.data = bytes(data)
        self.method = method
        self.dos_time = dos_time
        self.dos_date = dos_date
        self.external_attr = external_attr
        self.crc32 = zlib.crc32(self.data)
        self._compressed: Optional[bytes] = None

    @property
    def compressed(self) -> bytes:
        """The bytes written to the zip for this entry."""
        if self._compressed is None:
            if self.method == "stored":
                self._compressed = self.data
            else:
                c = zlib.compressobj(9, zlib.DEFLATED, -15)
                self._compressed = c.compress(self.data) + c.flush()
        return self._compressed

    def __repr__(self) -> str:
        return f"NewData({len(self.data)} bytes, method={self.method!r})"


Source = Union[RawCopy, NewData]


def _check_new_name(name: str) -> None:
    """Refuse names that would be unsafe or invalid inside an APK."""
    if not isinstance(name, str) or not name:
        raise ZipError("entry name must be a non-empty string")
    if name.startswith("/") or "\\" in name or "\0" in name:
        raise ZipError(f"invalid entry name {name!r} (absolute path, backslash or NUL)")
    if any(part in ("..", ".") for part in name.split("/")):
        raise ZipError(f"invalid entry name {name!r} (contains '.' or '..' path components)")
    if len(name.encode("utf-8")) > 0xFFFF:
        raise ZipError(f"entry name too long: {name[:60]!r}...")


class ZipPlan:
    """Ordered map of output entry name -> :class:`RawCopy` or :class:`NewData`.

    The order of insertion is the order of entries in the written zip; replacing an entry
    keeps its position.
    """

    def __init__(self):
        self._items: dict[str, Source] = {}

    def add_raw(self, zipin: ZipIn, entry: Union[Entry, str]) -> None:
        """Append a raw copy of ``entry`` (an Entry of ``zipin`` or its name)."""
        e = zipin._entry(entry)
        if e.name in self._items:
            raise ZipError(f"the output already has an entry named {e.name!r}")
        if e.method not in _NAME_BY_METHOD:
            raise ZipError(f"{zipin.path}: entry {e.name!r} uses compression method {e.method}; "
                           "Android only supports stored (0) and deflate (8)")
        if e.flags & _FLAG_ENCRYPTED:
            raise ZipError(f"{zipin.path}: entry {e.name!r} is encrypted")
        self._items[e.name] = RawCopy(zipin, e)

    def add_bytes(self, name: str, data: bytes, method: str = "deflate") -> None:
        """Append a new entry with ``data`` written as ``method`` ('stored' or 'deflate')."""
        _check_new_name(name)
        if name in self._items:
            raise ZipError(f"the output already has an entry named {name!r}")
        self._items[name] = NewData(data, method)

    def replace_bytes(self, name: str, data: bytes, method: Optional[str] = None) -> None:
        """Replace an existing entry's content, keeping its position and timestamp.

        ``method=None`` keeps the old entry's method.
        """
        old = self.get_source(name)
        if method is None:
            method = old.method
        if isinstance(old, RawCopy):
            e = old.entry
            new = NewData(data, method, dos_time=e.dos_time, dos_date=e.dos_date,
                          external_attr=e.external_attr)
        else:
            new = NewData(data, method, dos_time=old.dos_time, dos_date=old.dos_date,
                          external_attr=old.external_attr)
        self._items[name] = new

    def remove(self, name: str) -> Source:
        """Remove an entry; returns its source. KeyError if absent."""
        try:
            return self._items.pop(name)
        except KeyError:
            raise KeyError(f"the output has no entry named {name!r}") from None

    def names(self) -> list[str]:
        """Entry names in output order."""
        return list(self._items)

    def has(self, name: str) -> bool:
        return name in self._items

    __contains__ = has

    def get_source(self, name: str) -> Source:
        """The source of entry ``name``. KeyError if absent."""
        try:
            return self._items[name]
        except KeyError:
            raise KeyError(f"the output has no entry named {name!r}") from None

    def items(self):
        """(name, source) pairs in output order."""
        return self._items.items()

    def __len__(self) -> int:
        return len(self._items)

    def __iter__(self):
        return iter(self._items)


# ---------------------------------------------------------------------------------------
# Writer
# ---------------------------------------------------------------------------------------


@dataclass
class _Written:
    name_bytes: bytes
    flags: int
    method: int
    dos_time: int
    dos_date: int
    crc32: int
    csize: int
    usize: int
    create_version: int
    extract_version: int
    internal_attr: int
    external_attr: int
    offset: int


def _alignment_for(name: str, align: int, so_align: int) -> int:
    return so_align if name.endswith(".so") else align


def _align_extra(offset_of_header: int, name_len: int, alignment: int) -> bytes:
    """Padding extra field (id 0xD935) that puts the data start on an ``alignment`` boundary."""
    if alignment <= 1:
        return b""
    base = offset_of_header + _LOCAL.size + name_len + 6
    pad = (-base) % alignment
    return struct.pack("<HHH", _ALIGN_EXTRA_ID, 2 + pad, alignment) + b"\0" * pad


def _same_file(a: str, b: str) -> bool:
    try:
        return os.path.samefile(a, b)
    except OSError:
        return False


def write(plan: ZipPlan, path: Union[str, os.PathLike], align: int = 4,
          so_align: int = 16384) -> dict:
    """Write ``plan`` as a zip at ``path``; returns {entries, bytes, stored, deflated}.

    Stored entries are aligned (``so_align`` for names ending in '.so', ``align`` otherwise);
    deflated entries are not. The file is written to ``path + '.tmp'`` and renamed into place
    on success, so a failed write never leaves a half-written zip at ``path``.
    """
    path = os.fspath(path)
    for value, label in ((align, "align"), (so_align, "so_align")):
        if not isinstance(value, int) or value < 1 or value > 0x8000:
            raise ValueError(f"{label} must be an integer between 1 and 32768")
    sources = {id(src.zipin): src.zipin for _, src in plan.items() if isinstance(src, RawCopy)}
    for zin in sources.values():
        if zin.closed:
            raise ZipError(f"cannot write {path}: source zip {zin.path} is closed")
        if _same_file(zin.path, path):
            raise ZipError(f"cannot write {path}: it is a source of this plan and would be "
                           "overwritten while being read")
    if len(plan) > 0xFFFF:
        raise ZipError(f"too many entries ({len(plan)}) for a non-ZIP64 zip")

    tmp = path + ".tmp"
    written: list[_Written] = []
    stored = deflated = 0
    try:
        with open(tmp, "wb") as out:
            offset = 0
            for name, src in plan.items():
                name_bytes = name.encode("utf-8")
                flags = _FLAG_UTF8 if not name.isascii() else 0
                if isinstance(src, RawCopy):
                    e = src.entry
                    method, crc, csize, usize = e.method, e.crc32, e.compressed_size, e.size
                    flags |= e.flags & 0x0006  # deflate option bits only
                    dos_time, dos_date = e.dos_time, e.dos_date
                    create_version, internal_attr, external_attr = (
                        e.create_version, e.internal_attr, e.external_attr)
                else:
                    method = _METHOD_BY_NAME[src.method]
                    crc, usize = src.crc32, len(src.data)
                    csize = len(src.compressed)
                    dos_time, dos_date = src.dos_time, src.dos_date
                    create_version, internal_attr, external_attr = 20, 0, src.external_attr
                extract_version = 20 if method == METHOD_DEFLATE or name.endswith("/") else 10
                if method == METHOD_STORED:
                    extra = _align_extra(offset, len(name_bytes),
                                         _alignment_for(name, align, so_align))
                    stored += 1
                else:
                    extra = b""
                    deflated += 1
                if offset > 0xFFFFFFFF or csize > 0xFFFFFFFF or usize > 0xFFFFFFFF:
                    raise ZipError(f"{name!r}: output too large for a non-ZIP64 zip")
                out.write(_LOCAL.pack(_SIG_LOCAL, extract_version, flags, method, dos_time,
                                      dos_date, crc, csize, usize, len(name_bytes), len(extra)))
                out.write(name_bytes)
                out.write(extra)
                copied = 0
                if isinstance(src, RawCopy):
                    for chunk in src.zipin.iter_raw(e):
                        out.write(chunk)
                        copied += len(chunk)
                else:
                    out.write(src.compressed)
                    copied = csize
                if copied != csize:
                    raise ZipError(f"{name!r}: copied {copied} bytes, expected {csize}")
                written.append(_Written(name_bytes, flags, method, dos_time, dos_date, crc,
                                        csize, usize, create_version, extract_version,
                                        internal_attr, external_attr, offset))
                offset += _LOCAL.size + len(name_bytes) + len(extra) + csize

            cd_offset = offset
            for w in written:
                out.write(_CENTRAL.pack(_SIG_CENTRAL, w.create_version, w.extract_version,
                                        w.flags, w.method, w.dos_time, w.dos_date, w.crc32,
                                        w.csize, w.usize, len(w.name_bytes), 0, 0, 0,
                                        w.internal_attr, w.external_attr, w.offset))
                out.write(w.name_bytes)
                offset += _CENTRAL.size + len(w.name_bytes)
            cd_size = offset - cd_offset
            if cd_offset > 0xFFFFFFFF or cd_size > 0xFFFFFFFF:
                raise ZipError("output too large for a non-ZIP64 zip")
            out.write(_EOCD.pack(_SIG_EOCD, 0, 0, len(written), len(written), cd_size,
                                 cd_offset, 0))
            offset += _EOCD.size
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
    return {"entries": len(written), "bytes": offset, "stored": stored, "deflated": deflated}


def verify_alignment(path: Union[str, os.PathLike], align: int = 4,
                     so_align: int = 16384) -> list[str]:
    """Check that every stored entry's data is aligned; returns a list of violations.

    Stored names ending in '.so' must start on a ``so_align`` boundary, other stored entries
    on an ``align`` boundary. Deflated entries are not checked. Raises :class:`ZipError` if
    the file cannot be parsed (including duplicate names).
    """
    problems: list[str] = []
    with ZipIn(path) as z:
        for e in z.entries:
            if e.method != METHOD_STORED:
                continue
            need = _alignment_for(e.name, align, so_align)
            if e.data_offset % need:
                problems.append(f"{e.name}: stored data at offset {e.data_offset} is not "
                                f"aligned to {need} bytes")
    return problems
