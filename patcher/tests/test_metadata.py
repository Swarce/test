"""Unit tests for sgmo_patcher.metadata.

Synthetic blobs are built in-test (valid header + a small literal table + a data region,
including one deliberately overlapping pair). The real-file test reads global-metadata.dat
from the player's own XAPK through SGMO_APK and skips cleanly without it.
"""

import heapq
import io
import os
import struct
import sys
import unittest
import zipfile

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(_HERE, ".."))

from sgmo_patcher import metadata as M  # noqa: E402
from sgmo_patcher.metadata import Metadata, MetadataError, compare  # noqa: E402

_APK = os.environ.get("SGMO_APK")
_HAS_APK = bool(_APK) and os.path.exists(_APK or "")
_METADATA_ENTRY = "assets/bin/Data/Managed/Metadata/global-metadata.dat"


def build_metadata(entries, data_region, version=31, header_len=24):
    """Build a minimal valid global-metadata.dat.

    ``entries`` is a list of ``(length, dataIndex)``; ``data_region`` the literal data bytes.
    Layout: a ``header_len``-byte header, then the literal table, then the data region.
    """
    header = bytearray(header_len)
    struct.pack_into("<Ii", header, 0, 0xFAB11BAF, version)
    lit_off = header_len
    lit_size = len(entries) * 8
    data_off = lit_off + lit_size
    data_size = len(data_region)
    struct.pack_into("<IIII", header, 8, lit_off, lit_size, data_off, data_size)
    blob = bytearray(header)
    for length, data_index in entries:
        blob += struct.pack("<II", length, data_index)
    blob += data_region
    return bytes(blob)


def overlapping_pair_count(md):
    """Total count of literal pairs whose (non-empty) byte ranges intersect, via a sweep."""
    intervals = []
    for i in range(md.literal_count):
        length, data_index = md.literal_entry(i)
        if length > 0:
            intervals.append((data_index, data_index + length))
    intervals.sort()
    active = []  # min-heap of end offsets
    pairs = 0
    for start, end in intervals:
        while active and active[0] <= start:
            heapq.heappop(active)
        pairs += len(active)
        heapq.heappush(active, end)
    return pairs


class SyntheticMetadataTest(unittest.TestCase):
    def setUp(self):
        # data region "helloworldabcdef": hello@0, world@5, llo@2 (overlaps hello), abcdef@10
        self.data_region = b"helloworldabcdef"
        self.entries = [(5, 0), (5, 5), (3, 2), (6, 10)]
        self.blob = build_metadata(self.entries, self.data_region)

    def test_parse_and_read(self):
        md = Metadata.parse(self.blob)
        self.assertEqual(md.version, 31)
        self.assertEqual(md.literal_count, 4)
        self.assertEqual(md.literal_str(0), "hello")
        self.assertEqual(md.literal_str(3), "abcdef")
        self.assertEqual(md.literal(1), b"world")
        self.assertEqual(md.literal_entry(2), (3, 2))

    def test_literal_str_round_trips(self):
        md = Metadata.parse(self.blob)
        for i in range(md.literal_count):
            self.assertEqual(md.literal(i).decode("utf-8"), md.literal_str(i))

    def test_find_literal(self):
        md = Metadata.parse(self.blob)
        self.assertEqual(md.find_literal("hello"), [0])
        self.assertEqual(md.find_literal("abcdef"), [3])
        self.assertEqual(md.find_literal("missing"), [])

    def test_overlapping_detected(self):
        md = Metadata.parse(self.blob)
        self.assertEqual(md.overlapping(0), [2])  # "hello" and "llo" share bytes
        self.assertEqual(md.overlapping(2), [0])
        self.assertEqual(md.overlapping(1), [])
        self.assertEqual(md.overlapping(3), [])
        self.assertEqual(overlapping_pair_count(md), 1)

    def test_rewrite_refuses_overlapping(self):
        md = Metadata.parse(self.blob)
        with self.assertRaises(MetadataError):
            md.rewrite_literal(0, "hi")  # #0 overlaps #2
        self.assertEqual(md.changes, [])

    def test_rewrite_refuses_too_long(self):
        md = Metadata.parse(self.blob)
        with self.assertRaises(MetadataError):
            md.rewrite_literal(1, "wayyytoolong")  # 12 bytes > 5
        self.assertEqual(md.changes, [])

    def test_rewrite_and_compare(self):
        md = Metadata.parse(self.blob)
        md.rewrite_literal(3, "xy")  # "abcdef" -> "xy", no overlap
        self.assertEqual(md.literal_str(3), "xy")
        self.assertEqual(md.literal_entry(3), (2, 10))
        self.assertEqual(len(md.changes), 1)
        change = md.changes[0]
        self.assertEqual(change["index"], 3)
        self.assertEqual(change["old"], "abcdef")
        self.assertEqual(change["new"], "xy")
        patched = md.to_bytes()
        self.assertEqual(len(patched), len(self.blob))
        # tail of the old range is zeroed.
        self.assertEqual(patched[change["fileOffset"]:change["fileOffset"] + 6], b"xy\x00\x00\x00\x00")
        self.assertEqual(compare(self.blob, patched, {3}), [])
        violations = compare(self.blob, patched, set())
        self.assertTrue(violations)

    def test_rewrite_equal_length(self):
        md = Metadata.parse(self.blob)
        md.rewrite_literal(1, "WORLD")  # same length, no overlap
        self.assertEqual(md.literal_str(1), "WORLD")
        self.assertEqual(compare(self.blob, md.to_bytes(), {1}), [])

    def test_bad_sanity_and_version(self):
        bad = bytearray(self.blob)
        struct.pack_into("<I", bad, 0, 0xDEADBEEF)
        with self.assertRaises(MetadataError):
            Metadata.parse(bytes(bad))
        bad_ver = bytearray(self.blob)
        struct.pack_into("<i", bad_ver, 4, 99)
        with self.assertRaises(MetadataError):
            Metadata.parse(bytes(bad_ver))

    def test_compare_length_change(self):
        md = Metadata.parse(self.blob)
        md.rewrite_literal(3, "xy")
        shorter = md.to_bytes()[:-1]
        self.assertTrue(compare(self.blob, shorter, {3}))


@unittest.skipUnless(_HAS_APK, "SGMO_APK is not set to an existing file")
class RealMetadataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(_APK) as z:
            apks = [n for n in z.namelist() if n.endswith(".apk")]
            base_name = next((n for n in apks if "config" not in n and "split" not in n),
                             apks[0] if apks else None)
            if base_name is None:
                raise unittest.SkipTest("SGMO_APK does not look like an XAPK")
            base = z.read(base_name)
        with zipfile.ZipFile(io.BytesIO(base)) as iz:
            cls.blob = iz.read(_METADATA_ENTRY)

    def test_version_and_literals(self):
        md = Metadata.parse(self.blob)
        self.assertEqual(md.version, 31)
        self.assertEqual(md.literal_str(19449),
                         "https://skgm-prod-game-api.skullgirlsmobile.com")
        self.assertEqual(md.literal_str(19448),
                         "https://skgm-prod-cdn.skullgirlsmobile.com/")

    def test_find_and_no_overlap(self):
        md = Metadata.parse(self.blob)
        self.assertEqual(md.find_literal("https://skgm-prod-game-api.skullgirlsmobile.com"),
                         [19449])
        self.assertEqual(md.find_literal("https://skgm-prod-cdn.skullgirlsmobile.com/"),
                         [19448])
        self.assertEqual(md.overlapping(19449), [])
        self.assertEqual(md.overlapping(19448), [])

    def test_rewrite_api_and_cdn(self):
        md = Metadata.parse(self.blob)
        md.rewrite_literal(19449, "http://127.0.0.1:47820")
        md.rewrite_literal(19448, "http://127.0.0.1:47820/cdn/")
        self.assertEqual(compare(self.blob, md.to_bytes(), {19448, 19449}), [])

    def test_overlapping_pair_count(self):
        # This build (Skullgirls Mobile 8.12.0, metadata v31, 24307 literals) has no
        # overlapping literal pairs at all.
        md = Metadata.parse(self.blob)
        self.assertEqual(md.literal_count, 24307)
        self.assertEqual(overlapping_pair_count(md), 0)


if __name__ == "__main__":
    unittest.main()
