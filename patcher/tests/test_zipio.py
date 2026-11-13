"""Unit tests for sgmo_patcher.zipio on synthetic zips (no game files)."""

import io
import os
import shutil
import struct
import sys
import tempfile
import unittest
import zipfile
import zlib

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from sgmo_patcher import zipio  # noqa: E402
from sgmo_patcher.zipio import NewData, RawCopy, ZipError, ZipIn, ZipPlan  # noqa: E402


def random_bytes(n, seed):
    """Deterministic incompressible-ish bytes."""
    out = bytearray()
    x = seed
    while len(out) < n:
        x = (x * 6364136223846793005 + 1442695040888963407) & 0xFFFFFFFFFFFFFFFF
        out += x.to_bytes(8, "little")
    return bytes(out[:n])


SAMPLE = {
    "AndroidManifest.xml": (zipfile.ZIP_DEFLATED, b"<manifest/>" * 50),
    "classes.dex": (zipfile.ZIP_STORED, b"dex\n035\0" + random_bytes(5000, 1)),
    "lib/arm64-v8a/libfoo.so": (zipfile.ZIP_STORED, b"\x7fELF" + random_bytes(9000, 2)),
    "lib/arm64-v8a/libbar.so": (zipfile.ZIP_DEFLATED, b"\x7fELF" + b"\0" * 20000),
    "res/raw/a.bin": (zipfile.ZIP_STORED, b"x"),
    "res/raw/empty": (zipfile.ZIP_STORED, b""),
    "assets/big.bin": (zipfile.ZIP_DEFLATED, random_bytes(3 * 1024 * 1024 + 17, 3)),
    "assets/dir/": (zipfile.ZIP_STORED, b""),
    "assets/café.txt": (zipfile.ZIP_DEFLATED, "naïve".encode() * 10),
    "resources.arsc": (zipfile.ZIP_STORED, random_bytes(777, 4)),
}


def make_zip(path, items=SAMPLE, date_time=(2020, 5, 6, 7, 8, 10), comment=b""):
    """Build a zip with Python's zipfile from {name: (method, data)}."""
    with zipfile.ZipFile(path, "w") as zf:
        for name, (method, data) in items.items():
            info = zipfile.ZipInfo(name, date_time=date_time)
            info.compress_type = method
            info.external_attr = 0o644 << 16
            zf.writestr(info, data)
        zf.comment = comment
    return path


def hand_zip(entries, zip64=False, eocd_disk=0, comment=b""):
    """Build a zip by hand: entries = [(name_bytes, data, local_name_bytes or None)].

    Stored only. With ``zip64`` the central directory uses ZIP64 placeholders and a ZIP64
    EOCD record + locator.
    """
    out = bytearray()
    cd = bytearray()
    for name, data, local_name in entries:
        crc = zlib.crc32(data)
        offset = len(out)
        lname = local_name if local_name is not None else name
        out += struct.pack("<IHHHHHIIIHH", 0x04034B50, 10, 0, 0, 0, 0x21, crc, len(data),
                           len(data), len(lname), 0)
        out += lname + data
        if zip64:
            extra = struct.pack("<HHQQQ", 1, 24, len(data), len(data), offset)
            cd += struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 45, 45, 0, 0, 0, 0x21, crc,
                              0xFFFFFFFF, 0xFFFFFFFF, len(name), len(extra), 0, 0, 0, 0,
                              0xFFFFFFFF)
            cd += name + extra
        else:
            cd += struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, 20, 10, 0, 0, 0, 0x21, crc,
                              len(data), len(data), len(name), 0, 0, 0, 0, 0, offset)
            cd += name
    cd_offset = len(out)
    out += cd
    n = len(entries)
    if zip64:
        z64_pos = len(out)
        out += struct.pack("<IQHHIIQQQQ", 0x06064B50, 44, 45, 45, 0, 0, n, n, len(cd), cd_offset)
        out += struct.pack("<IIQI", 0x07064B50, 0, z64_pos, 1)
        out += struct.pack("<IHHHHIIH", 0x06054B50, 0xFFFF, 0xFFFF, 0xFFFF, 0xFFFF,
                           0xFFFFFFFF, 0xFFFFFFFF, len(comment)) + comment
    else:
        out += struct.pack("<IHHHHIIH", 0x06054B50, eocd_disk, eocd_disk, n, n, len(cd),
                           cd_offset, len(comment)) + comment
    return bytes(out)


class TempDirTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="sgmo-zipio-test-")
        self.addCleanup(shutil.rmtree, self.tmp, True)

    def p(self, name):
        return os.path.join(self.tmp, name)

    def write_bytes(self, name, data):
        with open(self.p(name), "wb") as f:
            f.write(data)
        return self.p(name)


class ZipInTest(TempDirTest):
    def test_entries_match_zipfile(self):
        path = make_zip(self.p("a.zip"))
        with ZipIn(path) as z, zipfile.ZipFile(path) as zf:
            infos = zf.infolist()
            self.assertEqual(z.names(), [i.filename for i in infos])
            for e, i in zip(z.entries, infos):
                self.assertEqual(e.method, i.compress_type)
                self.assertEqual(e.crc32, i.CRC)
                self.assertEqual(e.size, i.file_size)
                self.assertEqual(e.compressed_size, i.compress_size)
                self.assertEqual(e.header_offset, i.header_offset)
                self.assertEqual(e.external_attr, i.external_attr)
                self.assertEqual(e.flags, i.flag_bits)
                self.assertEqual(e.dos_date >> 9, 2020 - 1980)
            self.assertIn("classes.dex", z)
            self.assertNotIn("nope", z)
            self.assertEqual(len(z), len(SAMPLE))

    def test_read_and_raw(self):
        path = make_zip(self.p("a.zip"))
        with ZipIn(path) as z, zipfile.ZipFile(path) as zf:
            for name, (method, data) in SAMPLE.items():
                self.assertEqual(z.read(name), data, name)
                raw = z.read_raw(name)
                if method == zipfile.ZIP_STORED:
                    self.assertEqual(raw, data)
                else:
                    self.assertEqual(zlib.decompress(raw, -15), data)
                self.assertEqual(zf.read(name), data)

    def test_data_offset_from_local_header(self):
        path = make_zip(self.p("a.zip"))
        with ZipIn(path) as z, open(path, "rb") as f:
            for e in z.entries:
                if e.method == zipio.METHOD_STORED:
                    f.seek(e.data_offset)
                    self.assertEqual(f.read(e.size), SAMPLE[e.name][1])

    def test_open_raw_is_seekable(self):
        path = make_zip(self.p("a.zip"))
        with ZipIn(path) as z:
            s = z.open_raw("classes.dex")
            self.assertEqual(s.read(4), b"dex\n")
            s.seek(-3, os.SEEK_END)
            self.assertEqual(s.read(), SAMPLE["classes.dex"][1][-3:])
            self.assertEqual(s.read(10), b"")
            s.seek(0)
            self.assertEqual(s.tell(), 0)

    def test_iter_data_chunks(self):
        path = make_zip(self.p("a.zip"))
        with ZipIn(path) as z:
            chunks = list(z.iter_data("assets/big.bin", chunk_size=4096))
            self.assertGreater(len(chunks), 1)
            self.assertEqual(b"".join(chunks), SAMPLE["assets/big.bin"][1])

    def test_crc_mismatch_detected(self):
        path = make_zip(self.p("a.zip"))
        with ZipIn(path) as z:
            off = z.get("classes.dex").data_offset
        with open(path, "r+b") as f:
            f.seek(off + 100)
            b = f.read(1)
            f.seek(off + 100)
            f.write(bytes([b[0] ^ 0xFF]))
        with ZipIn(path) as z:
            with self.assertRaisesRegex(ZipError, "CRC mismatch"):
                z.read("classes.dex")
            self.assertEqual(z.read("res/raw/a.bin"), b"x")

    def test_corrupt_deflate_detected(self):
        path = make_zip(self.p("a.zip"))
        with ZipIn(path) as z:
            e = z.get("AndroidManifest.xml")
            off, n = e.data_offset, e.compressed_size
        with open(path, "r+b") as f:
            f.seek(off)
            f.write(b"\xff" * n)
        with ZipIn(path) as z, self.assertRaises(ZipError):
            z.read("AndroidManifest.xml")

    def test_unicode_names(self):
        path = make_zip(self.p("a.zip"))
        with ZipIn(path) as z:
            e = z.get("assets/café.txt")
            self.assertIsNotNone(e)
            self.assertTrue(e.flags & 0x800)

    def test_comment_and_trailing_signature_in_comment(self):
        comment = b"hello PK\x05\x06 not an eocd"
        path = make_zip(self.p("a.zip"), comment=comment)
        with ZipIn(path) as z:
            self.assertEqual(z.comment, comment)
            self.assertEqual(len(z.entries), len(SAMPLE))

    def test_duplicate_names_refused(self):
        data = hand_zip([(b"a.txt", b"one", None), (b"a.txt", b"two", None)])
        path = self.write_bytes("dup.zip", data)
        with zipfile.ZipFile(path) as zf:  # zipfile itself accepts it
            self.assertEqual(len(zf.infolist()), 2)
        with self.assertRaisesRegex(ZipError, "duplicate entry name 'a.txt'"):
            ZipIn(path)

    def test_hand_zip_ok(self):
        path = self.write_bytes("h.zip", hand_zip([(b"a", b"1", None), (b"b/c", b"22", None)]))
        with ZipIn(path) as z:
            self.assertEqual(z.names(), ["a", "b/c"])
            self.assertEqual(z.read("b/c"), b"22")
            self.assertFalse(z.zip64)

    def test_zip64(self):
        path = self.write_bytes("z64.zip", hand_zip(
            [(b"a", b"alpha", None), (b"b", b"beta", None)], zip64=True))
        with ZipIn(path) as z:
            self.assertTrue(z.zip64)
            self.assertEqual(z.read("a"), b"alpha")
            self.assertEqual(z.read("b"), b"beta")
            self.assertEqual(z.get("b").header_offset, 30 + 1 + 5)
        with zipfile.ZipFile(path) as zf:
            self.assertEqual(zf.read("b"), b"beta")

    def test_multi_disk_refused(self):
        path = self.write_bytes("md.zip", hand_zip([(b"a", b"1", None)], eocd_disk=1))
        with self.assertRaisesRegex(ZipError, "multi-disk"):
            ZipIn(path)

    def test_local_name_mismatch_refused(self):
        path = self.write_bytes("ln.zip", hand_zip([(b"good", b"1", b"evil")]))
        with ZipIn(path) as z:
            with self.assertRaisesRegex(ZipError, "does not match"):
                z.read("good")

    def test_not_a_zip(self):
        with self.assertRaisesRegex(ZipError, "not a zip"):
            ZipIn(self.write_bytes("x.zip", b"hello world" * 10))
        with self.assertRaisesRegex(ZipError, "too small"):
            ZipIn(self.write_bytes("y.zip", b"PK"))

    def test_truncated_zip(self):
        path = make_zip(self.p("a.zip"))
        with open(path, "rb") as f:
            data = f.read()
        with self.assertRaises(ZipError):
            ZipIn(self.write_bytes("t.zip", data[:-5]))

    def test_unsupported_method(self):
        path = make_zip(self.p("a.zip"))
        with ZipIn(path) as z:
            cd_pos = z.cd_offset
        with open(path, "r+b") as f:  # set the first central entry's method to 12 (bzip2)
            f.seek(cd_pos + 10)
            f.write(struct.pack("<H", 12))
        with ZipIn(path) as z:
            first = z.entries[0].name
            with self.assertRaisesRegex(ZipError, "compression method 12"):
                z.read(first)
            self.assertTrue(z.read_raw(first))  # raw access still works
            with self.assertRaisesRegex(ZipError, "method 12"):
                ZipPlan().add_raw(z, first)

    def test_closed(self):
        z = ZipIn(make_zip(self.p("a.zip")))
        z.close()
        z.close()
        self.assertTrue(z.closed)
        with self.assertRaisesRegex(ZipError, "closed"):
            z.read("classes.dex")

    def test_missing_name(self):
        with ZipIn(make_zip(self.p("a.zip"))) as z, self.assertRaises(KeyError):
            z.read("missing")


class PlanTest(TempDirTest):
    def test_plan_operations(self):
        path = make_zip(self.p("a.zip"))
        with ZipIn(path) as z:
            plan = ZipPlan()
            for e in z.entries:
                plan.add_raw(z, e)
            self.assertEqual(plan.names(), z.names())
            with self.assertRaisesRegex(ZipError, "already has"):
                plan.add_raw(z, "classes.dex")
            with self.assertRaisesRegex(ZipError, "already has"):
                plan.add_bytes("classes.dex", b"")
            self.assertIsInstance(plan.get_source("classes.dex"), RawCopy)
            plan.replace_bytes("classes.dex", b"new dex")
            src = plan.get_source("classes.dex")
            self.assertIsInstance(src, NewData)
            self.assertEqual(src.method, "stored")  # kept from the old entry
            self.assertEqual(src.dos_date, z.get("classes.dex").dos_date)
            plan.replace_bytes("AndroidManifest.xml", b"m", method="stored")
            self.assertEqual(plan.get_source("AndroidManifest.xml").method, "stored")
            plan.replace_bytes("AndroidManifest.xml", b"m2")
            self.assertEqual(plan.get_source("AndroidManifest.xml").method, "stored")
            self.assertEqual(plan.names(), z.names())  # positions kept
            removed = plan.remove("res/raw/a.bin")
            self.assertIsInstance(removed, RawCopy)
            self.assertFalse(plan.has("res/raw/a.bin"))
            with self.assertRaises(KeyError):
                plan.remove("res/raw/a.bin")
            with self.assertRaises(KeyError):
                plan.replace_bytes("nope", b"")
            plan.add_bytes("assets/new.txt", b"hi", "stored")
            self.assertEqual(plan.names()[-1], "assets/new.txt")
            self.assertIn("assets/new.txt", plan)

    def test_bad_names_and_methods(self):
        plan = ZipPlan()
        for bad in ("", "/abs", "a/../b", "a\\b", "./a"):
            with self.assertRaises(ZipError, msg=bad):
                plan.add_bytes(bad, b"")
        with self.assertRaises(ValueError):
            plan.add_bytes("a", b"", "bzip2")

    def test_entry_of_other_zip_refused(self):
        a = ZipIn(make_zip(self.p("a.zip")))
        b = ZipIn(make_zip(self.p("b.zip")))
        self.addCleanup(a.close)
        self.addCleanup(b.close)
        with self.assertRaisesRegex(ZipError, "different zip"):
            ZipPlan().add_raw(a, b.entries[0])


class WriteTest(TempDirTest):
    def full_plan(self, z):
        plan = ZipPlan()
        for e in z.entries:
            plan.add_raw(z, e)
        return plan

    def test_round_trip_raw_copy(self):
        src = make_zip(self.p("a.zip"))
        out = self.p("out.zip")
        with ZipIn(src) as z:
            summary = zipio.write(self.full_plan(z), out)
            with open(out, "rb") as f:
                out_bytes = f.read()
        self.assertEqual(summary["entries"], len(SAMPLE))
        self.assertEqual(summary["bytes"], len(out_bytes))
        n_stored = sum(1 for m, _ in SAMPLE.values() if m == zipfile.ZIP_STORED)
        self.assertEqual(summary["stored"], n_stored)
        self.assertEqual(summary["deflated"], len(SAMPLE) - n_stored)
        with zipfile.ZipFile(out) as zf, zipfile.ZipFile(src) as zs:
            self.assertIsNone(zf.testzip())
            self.assertEqual(zf.namelist(), zs.namelist())
            for a, b in zip(zf.infolist(), zs.infolist()):
                self.assertEqual(a.CRC, b.CRC)
                self.assertEqual(a.compress_type, b.compress_type)
                self.assertEqual(a.compress_size, b.compress_size)
                self.assertEqual(a.date_time, b.date_time)
                self.assertEqual(a.external_attr, b.external_attr)
                self.assertEqual(zf.read(a.filename), SAMPLE[a.filename][1])
        with ZipIn(out) as z2, ZipIn(src) as z1:
            for a, b in zip(z2.entries, z1.entries):
                self.assertEqual(z2.read_raw(a), z1.read_raw(b))  # compressed bytes copied
        self.assertEqual(zipio.verify_alignment(out), [])
        self.assertFalse(os.path.exists(out + ".tmp"))

    def test_alignment(self):
        src = make_zip(self.p("a.zip"))
        with ZipIn(src) as z:
            for align, so_align in ((4, 16384), (4, 4096), (8, 64), (1, 1)):
                out = self.p(f"out-{align}-{so_align}.zip")
                zipio.write(self.full_plan(z), out, align=align, so_align=so_align)
                self.assertEqual(zipio.verify_alignment(out, align, so_align), [])
                with ZipIn(out) as o:
                    so = o.get("lib/arm64-v8a/libfoo.so")
                    self.assertEqual(so.data_offset % so_align, 0)
                    for e in o.entries:
                        if e.method == zipio.METHOD_STORED:
                            self.assertEqual(e.data_offset % align, 0, e.name)
                with zipfile.ZipFile(out) as zf:
                    self.assertIsNone(zf.testzip())

    def test_alignment_extra_is_android_style(self):
        src = make_zip(self.p("a.zip"))
        out = self.p("out.zip")
        with ZipIn(src) as z:
            zipio.write(self.full_plan(z), out)
        with ZipIn(out) as o, open(out, "rb") as f:
            e = o.get("lib/arm64-v8a/libfoo.so")
            f.seek(e.header_offset + 26)
            nlen, elen = struct.unpack("<HH", f.read(4))
            f.seek(e.header_offset + 30 + nlen)
            hid, hlen, al = struct.unpack("<HHH", f.read(6))
            self.assertEqual((hid, hlen + 4, al), (0xD935, elen, 16384))
            d = o.get("AndroidManifest.xml")  # deflated: no padding
            f.seek(d.header_offset + 28)
            self.assertEqual(struct.unpack("<H", f.read(2))[0], 0)

    def test_verify_alignment_reports_violations(self):
        items = {"ab": (zipfile.ZIP_STORED, b"1234"), "lib/x.so": (zipfile.ZIP_STORED, b"elf")}
        path = make_zip(self.p("m.zip"), items)  # zipfile does not align
        problems = zipio.verify_alignment(path)
        with ZipIn(path) as z:
            expected = [n for n in items
                        if z.get(n).data_offset % (16384 if n.endswith(".so") else 4)]
        self.assertTrue(expected)
        self.assertEqual(len(problems), len(expected))
        self.assertTrue(all("not aligned" in p for p in problems))

    def test_determinism(self):
        src = make_zip(self.p("a.zip"))
        outs = []
        for i in range(2):
            with ZipIn(src) as z:  # fresh reader each time
                plan = self.full_plan(z)
                plan.replace_bytes("classes.dex", b"patched dex")
                plan.add_bytes("assets/new.bin", random_bytes(10000, 9), "deflate")
                plan.add_bytes("lib/arm64-v8a/libnew.so", b"\x7fELF new", "stored")
                out = self.p(f"det{i}.zip")
                zipio.write(plan, out)
                with open(out, "rb") as f:
                    outs.append(f.read())
        self.assertEqual(outs[0], outs[1])

    def test_new_entries(self):
        src = make_zip(self.p("a.zip"))
        out = self.p("out.zip")
        with ZipIn(src) as z:
            plan = self.full_plan(z)
            plan.replace_bytes("classes.dex", b"patched dex")
            plan.replace_bytes("AndroidManifest.xml", b"<m/>" * 100)
            plan.remove("assets/big.bin")
            plan.add_bytes("assets/new.bin", b"abc" * 1000, "deflate")
            plan.add_bytes("assets/新.txt", b"utf8 name", "stored")
            plan.add_bytes("lib/arm64-v8a/libnew.so", b"\x7fELF new", "stored")
            summary = zipio.write(plan, out)
        self.assertEqual(summary["entries"], len(SAMPLE) - 1 + 3)
        with zipfile.ZipFile(out) as zf:
            self.assertIsNone(zf.testzip())
            self.assertNotIn("assets/big.bin", zf.namelist())
            self.assertEqual(zf.read("classes.dex"), b"patched dex")
            self.assertEqual(zf.getinfo("classes.dex").compress_type, zipfile.ZIP_STORED)
            self.assertEqual(zf.getinfo("classes.dex").date_time, (2020, 5, 6, 7, 8, 10))
            self.assertEqual(zf.read("AndroidManifest.xml"), b"<m/>" * 100)
            self.assertEqual(zf.getinfo("AndroidManifest.xml").compress_type,
                             zipfile.ZIP_DEFLATED)
            info = zf.getinfo("assets/new.bin")
            self.assertEqual(info.compress_type, zipfile.ZIP_DEFLATED)
            self.assertEqual(info.date_time, (1981, 1, 1, 1, 1, 2))
            self.assertEqual(zf.read("assets/new.bin"), b"abc" * 1000)
            u = zf.getinfo("assets/新.txt")
            self.assertTrue(u.flag_bits & 0x800)
            self.assertFalse(zf.getinfo("assets/new.bin").flag_bits & 0x800)
            self.assertEqual(zf.namelist()[-3:], ["assets/new.bin", "assets/新.txt",
                                                  "lib/arm64-v8a/libnew.so"])
        self.assertEqual(zipio.verify_alignment(out), [])

    def test_no_data_descriptors(self):
        class Unseekable(io.RawIOBase):
            def __init__(self, f):
                self.f = f

            def writable(self):
                return True

            def write(self, b):
                return self.f.write(b)

        src = self.p("dd.zip")
        with open(src, "wb") as raw:
            with zipfile.ZipFile(Unseekable(raw), "w") as zf:
                zf.writestr("a.txt", b"hello" * 100, compress_type=zipfile.ZIP_DEFLATED)
                zf.writestr("b.bin", b"stored", compress_type=zipfile.ZIP_STORED)
        with ZipIn(src) as z:
            self.assertTrue(z.get("a.txt").flags & 0x8)  # source uses a data descriptor
            out = self.p("out.zip")
            zipio.write(self.full_plan(z), out)
        with zipfile.ZipFile(out) as zf:
            self.assertIsNone(zf.testzip())
            for info in zf.infolist():
                self.assertFalse(info.flag_bits & 0x8)
        with open(out, "rb") as f:  # local header carries the real sizes and CRC
            hdr = struct.unpack("<IHHHHHIIIHH", f.read(30))
        self.assertEqual(hdr[6], zlib.crc32(b"hello" * 100))
        self.assertEqual(hdr[8], 500)
        self.assertNotEqual(hdr[7], 0)

    def test_merge_two_sources(self):
        a = make_zip(self.p("a.zip"))
        b = make_zip(self.p("b.zip"), {"lib/arm64-v8a/libsplit.so": (zipfile.ZIP_STORED,
                                                                      random_bytes(5000, 7)),
                                       "AndroidManifest.xml": (zipfile.ZIP_DEFLATED, b"split")})
        out = self.p("merged.zip")
        with ZipIn(a) as za, ZipIn(b) as zb:
            plan = self.full_plan(za)
            for e in zb.entries:
                if e.name.startswith("lib/"):
                    plan.add_raw(zb, e)
            zipio.write(plan, out)
        with zipfile.ZipFile(out) as zf:
            self.assertIsNone(zf.testzip())
            self.assertEqual(zf.read("lib/arm64-v8a/libsplit.so"), random_bytes(5000, 7))
            self.assertEqual(zf.read("AndroidManifest.xml"), SAMPLE["AndroidManifest.xml"][1])
        self.assertEqual(zipio.verify_alignment(out), [])

    def test_refuse_overwriting_source(self):
        src = make_zip(self.p("a.zip"))
        with ZipIn(src) as z:
            with self.assertRaisesRegex(ZipError, "overwritten"):
                zipio.write(self.full_plan(z), src)
            with zipfile.ZipFile(src) as zf:
                self.assertIsNone(zf.testzip())

    def test_refuse_closed_source(self):
        src = make_zip(self.p("a.zip"))
        z = ZipIn(src)
        plan = self.full_plan(z)
        z.close()
        with self.assertRaisesRegex(ZipError, "closed"):
            zipio.write(plan, self.p("out.zip"))

    def test_failed_write_leaves_nothing(self):
        src = make_zip(self.p("a.zip"))
        out = self.p("out.zip")
        with ZipIn(src) as z:
            plan = self.full_plan(z)
            real_iter_raw = z.iter_raw

            def failing_iter_raw(entry, chunk_size=1 << 20):
                if entry.name == "assets/big.bin":
                    yield next(real_iter_raw(entry, 1024))
                    raise ZipError("simulated read failure")
                yield from real_iter_raw(entry, chunk_size)

            z.iter_raw = failing_iter_raw
            with self.assertRaisesRegex(ZipError, "simulated"):
                zipio.write(plan, out)
        self.assertFalse(os.path.exists(out))
        self.assertFalse(os.path.exists(out + ".tmp"))

    def test_empty_plan(self):
        out = self.p("empty.zip")
        summary = zipio.write(ZipPlan(), out)
        self.assertEqual(summary, {"entries": 0, "bytes": 22, "stored": 0, "deflated": 0})
        with zipfile.ZipFile(out) as zf:
            self.assertEqual(zf.namelist(), [])

    def test_bad_alignment_args(self):
        with self.assertRaises(ValueError):
            zipio.write(ZipPlan(), self.p("x.zip"), align=0)


if __name__ == "__main__":
    unittest.main()
