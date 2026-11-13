"""Tests for sgmo_patcher.inputs.

The unit tests build synthetic APKs whose "AndroidManifest.xml" is JSON and inject a fake
manifest reader, so they do not depend on sgmo_patcher.axml. The real-file tests read the
player's copy from the SGMO_APK environment variable (XAPK, .apks or folder) and are skipped
without it.
"""

import io
import json
import os
import shutil
import sys
import tempfile
import time
import unittest
import zipfile

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))

from sgmo_patcher import inputs, zipio  # noqa: E402
from sgmo_patcher.inputs import InputError  # noqa: E402

PKG = "com.example.game"


def fake_reader(data):
    """Manifest reader for synthetic APKs: the manifest is JSON."""
    return json.loads(data.decode("utf-8"))


def zip_bytes(members):
    """A zip as bytes from {name: bytes or (bytes, method)}."""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as zf:
        for name, value in members.items():
            data, method = value if isinstance(value, tuple) else (value, zipfile.ZIP_DEFLATED)
            zf.writestr(name, data, method)
    return buf.getvalue()


def apk_bytes(split=None, package=PKG, version_code=266, version_name="8.12.0"):
    """A synthetic APK as bytes; its AndroidManifest.xml is JSON (see fake_reader)."""
    manifest = {"package": package, "versionCode": version_code,
                "versionName": version_name, "split": split}
    members = {"AndroidManifest.xml": json.dumps(manifest).encode()}
    if split is None:
        members["classes.dex"] = (b"dex\n035\0" + b"\1" * 100, zipfile.ZIP_STORED)
        members["resources.arsc"] = (b"\2" * 64, zipfile.ZIP_STORED)
    else:
        members["lib/arm64-v8a/libgame.so"] = b"\x7fELF" + b"\3" * 500
    return zip_bytes(members)


def bundle(path, members, method=zipfile.ZIP_STORED):
    """Write a zip with {name: bytes} members (XAPK / APKS style)."""
    with zipfile.ZipFile(path, "w") as zf:
        for name, data in members.items():
            zf.writestr(name, data, method)
    return path


class InputsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="sgmo-inputs-test-")
        self.addCleanup(shutil.rmtree, self.tmp, True)
        self.work = os.path.join(self.tmp, "work")  # parent for load()'s temp folders
        os.mkdir(self.work)

    def p(self, *parts):
        return os.path.join(self.tmp, *parts)

    def write(self, name, data):
        path = self.p(name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
        return path

    def load(self, paths):
        return inputs.load(paths, manifest_reader=fake_reader, tmp_dir=self.work)

    def assert_no_temp_left(self):
        self.assertEqual(os.listdir(self.work), [])

    def xapk(self, name="game.xapk", **kw):
        meta = {"package_name": kw.get("meta_package", PKG),
                "version_code": kw.get("meta_version", "266"),
                "split_apks": [{"file": f"{PKG}.apk", "id": "base"},
                               {"file": "config.arm64_v8a.apk", "id": "config.arm64_v8a"}]}
        return bundle(self.p(name), {
            f"{PKG}.apk": apk_bytes(),
            "config.arm64_v8a.apk": apk_bytes("config.arm64_v8a"),
            "icon.png": b"\x89PNG fake",
            "manifest.json": json.dumps(meta).encode(),
        })

    # -- accepted layouts ------------------------------------------------------------------

    def test_xapk(self):
        path = self.xapk()
        with self.load([path]) as s:
            self.assertEqual(s.package, PKG)
            self.assertEqual(s.version_code, 266)
            self.assertEqual(s.version_name, "8.12.0")
            self.assertEqual(s.split_names, ["config.arm64_v8a"])
            self.assertEqual(s.sources, [f"{path}!{PKG}.apk", f"{path}!config.arm64_v8a.apk"])
            self.assertIn("classes.dex", s.base)
            self.assertIn("lib/arm64-v8a/libgame.so", s.splits[0])
            self.assertIs(s.split("config.arm64_v8a"), s.splits[0])
            self.assertIsNone(s.split("config.x86"))
            self.assertEqual(sorted(s.ignored), [f"{path}!icon.png", f"{path}!manifest.json"])
            self.assertEqual(s.warnings, [])
            temp = s.temp_dir
            self.assertTrue(os.path.isdir(temp))
            self.assertTrue(temp.startswith(self.work))
        self.assertFalse(os.path.exists(temp))
        self.assertTrue(s.base.closed)
        self.assertTrue(s.splits[0].closed)
        self.assert_no_temp_left()

    def test_xapk_manifest_json_mismatch_is_a_warning(self):
        path = self.xapk(meta_version="999")
        with self.load(path) as s:  # a single str is accepted too
            self.assertEqual(s.version_code, 266)
            self.assertEqual(len(s.warnings), 1)
            self.assertIn("versionCode 999", s.warnings[0])

    def test_apks_with_deflated_members(self):
        path = bundle(self.p("game.apks"), {
            "toc.pb": b"\x0a\x00",
            "splits/base-master.apk": apk_bytes(),
            "splits/base-arm64_v8a.apk": apk_bytes("config.arm64_v8a"),
            "splits/base-xxhdpi.apk": apk_bytes("config.xxhdpi"),
        }, method=zipfile.ZIP_DEFLATED)
        with self.load([path]) as s:
            self.assertEqual(s.split_names, ["config.arm64_v8a", "config.xxhdpi"])
            self.assertEqual(s.sources[0], f"{path}!splits/base-master.apk")
            self.assertEqual(s.ignored, [f"{path}!toc.pb"])
            self.assertEqual(s.base.read("resources.arsc"), b"\2" * 64)

    def test_folder(self):
        self.write("phone/base.apk", apk_bytes())
        self.write("phone/split_config.arm64_v8a.apk", apk_bytes("config.arm64_v8a"))
        self.write("phone/readme.txt", b"hi")
        self.write("phone/.hidden/other.apk", b"not even a zip")
        with self.load([self.p("phone")]) as s:
            self.assertEqual(s.sources, [self.p("phone", "base.apk"),
                                         self.p("phone", "split_config.arm64_v8a.apk")])
            self.assertEqual(s.ignored, [self.p("phone", "readme.txt")])
            self.assertIsNone(s.temp_dir)  # nothing extracted
        self.assert_no_temp_left()

    def test_explicit_apks_any_order(self):
        split = self.write("split_config.arm64_v8a.apk", apk_bytes("config.arm64_v8a"))
        base = self.write("base.apk", apk_bytes())
        with self.load([split, base]) as s:
            self.assertEqual(s.sources, [base, split])
            self.assertEqual(s.base.path, base)

    def test_base_only(self):
        base = self.write("base.apk", apk_bytes())
        with self.load([base]) as s:
            self.assertEqual(s.splits, [])
            self.assertEqual(s.split_names, [])

    def test_recognised_by_content_not_extension(self):
        base = self.write("game.bin", apk_bytes())
        bun = bundle(self.p("splits.zip"), {"a.apk": apk_bytes("config.arm64_v8a")})
        with self.load([base, bun]) as s:
            self.assertEqual(s.split_names, ["config.arm64_v8a"])

    def test_same_file_twice(self):
        base = self.write("base.apk", apk_bytes())
        with self.load([base, base]) as s:
            self.assertEqual(len(s.files), 1)
            self.assertIn("more than once", s.warnings[0])

    def test_unsafe_member_names_stay_in_temp(self):
        path = bundle(self.p("evil.apks"), {"../../escape.apk": apk_bytes(),
                                             "/abs/x.apk": apk_bytes("config.arm64_v8a")})
        with self.load([path]) as s:
            for f in s.files:
                self.assertEqual(os.path.dirname(f.path), s.temp_dir)
        self.assertFalse(os.path.exists(self.p("escape.apk")))
        self.assert_no_temp_left()

    # -- refused inputs --------------------------------------------------------------------

    def assert_refused(self, paths, *fragments):
        with self.assertRaises(InputError) as cm:
            self.load(paths)
        msg = str(cm.exception)
        for frag in fragments:
            self.assertIn(frag, msg)
        self.assert_no_temp_left()
        return msg

    def test_no_paths(self):
        self.assert_refused([], "No input files")

    def test_missing_path(self):
        self.assert_refused([self.p("nope.xapk")], "Cannot find", "nope.xapk", "Expected")

    def test_not_a_zip(self):
        path = self.write("game.xapk", b"this is not a zip file at all" * 10)
        self.assert_refused([path], "game.xapk", "not a valid zip", "Expected")

    def test_zip_without_apks(self):
        path = bundle(self.p("photos.zip"), {"a.png": b"x", "b.txt": b"y"})
        self.assert_refused([path], "photos.zip", "no .apk files inside", "a.png")

    def test_folder_without_apks(self):
        self.write("empty/notes.txt", b"x")
        self.xapk("empty/game.xapk")
        self.assert_refused([self.p("empty")], "contains no .apk files", "game.xapk")

    def test_inner_apk_without_manifest(self):
        not_an_apk = zip_bytes({"x": b"1"})
        path = bundle(self.p("odd.xapk"), {"base.apk": apk_bytes(), "other.apk": not_an_apk})
        self.assert_refused([path], "odd.xapk!other.apk", "no AndroidManifest.xml")

    def test_no_base(self):
        s = self.write("split_config.arm64_v8a.apk", apk_bytes("config.arm64_v8a"))
        self.assert_refused([s], "No base APK", "split_config.arm64_v8a.apk",
                            "split 'config.arm64_v8a'")

    def test_two_bases(self):
        a = self.write("a/base.apk", apk_bytes())
        b = self.write("b/base.apk", apk_bytes(version_code=265))
        self.assert_refused([a, b], "2 base APKs", "versionCode 265", "versionCode 266")

    def test_split_of_other_package(self):
        base = self.write("base.apk", apk_bytes())
        s = self.write("split.apk", apk_bytes("config.arm64_v8a", package="com.other"))
        self.assert_refused([base, s], "different app", "com.other", PKG)

    def test_split_of_other_version(self):
        base = self.write("base.apk", apk_bytes())
        s = self.write("split.apk", apk_bytes("config.arm64_v8a", version_code=265))
        self.assert_refused([base, s], "different version", "versionCode 265")

    def test_split_given_twice(self):
        base = self.write("base.apk", apk_bytes())
        s1 = self.write("s1.apk", apk_bytes("config.arm64_v8a"))
        s2 = self.write("s2.apk", apk_bytes("config.arm64_v8a"))
        self.assert_refused([base, s1, s2], "given twice", "s1.apk", "s2.apk")

    def test_reader_failure_is_friendly(self):
        base = self.write("base.apk", apk_bytes())

        def broken(data):
            raise ValueError("bad chunk")

        with self.assertRaises(InputError) as cm:
            inputs.load([base], manifest_reader=broken, tmp_dir=self.work)
        self.assertIn("Cannot read the AndroidManifest.xml of", str(cm.exception))
        self.assertIn("bad chunk", str(cm.exception))

    def test_bad_version_code(self):
        base = self.write("base.apk", apk_bytes(version_code="abc"))
        self.assert_refused([base], "no valid versionCode", "'abc'")

    def test_damaged_bundle_member(self):
        path = self.xapk()
        with zipio.ZipIn(path) as z:
            e = z.get(f"{PKG}.apk")
            off = e.data_offset + 40
        with open(path, "r+b") as f:
            f.seek(off)
            b = f.read(1)
            f.seek(off)
            f.write(bytes([b[0] ^ 0xFF]))
        self.assert_refused([path], "Cannot extract", f"{PKG}.apk", "download it again")

    def test_collect_only(self):
        path = self.xapk()
        cands, ignored, warnings, temp = inputs.collect([path], tmp_dir=self.work)
        try:
            self.assertEqual([c.source for c in cands],
                             [f"{path}!{PKG}.apk", f"{path}!config.arm64_v8a.apk"])
            for c in cands:
                self.assertTrue(os.path.isfile(c.path))
        finally:
            shutil.rmtree(temp)


SGMO_APK = os.environ.get("SGMO_APK")


def _axml_available():
    try:
        import sgmo_patcher.axml as axml  # noqa: F401
    except ImportError:
        return False
    return hasattr(axml, "manifest_info")


@unittest.skipUnless(SGMO_APK, "set SGMO_APK to your XAPK/.apks/folder to run real-file tests")
class RealFileTest(unittest.TestCase):
    """Checks on the player's own copy (read-only; output goes to a temp folder)."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp(prefix="sgmo-inputs-real-")
        cls.cands, cls.ignored, cls.warnings, cls.temp_dir = inputs.collect(
            [SGMO_APK], tmp_dir=cls.tmp)

    @classmethod
    def tearDownClass(cls):
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def zips(self):
        return [c.path for c in self.cands]

    def base_and_split(self):
        """Base = the APK with classes.dex; split = the one with lib/ entries."""
        base = split = None
        for path in self.zips():
            with zipfile.ZipFile(path) as zf:
                names = zf.namelist()
            if "classes.dex" in names:
                base = path
            elif any(n.startswith("lib/") for n in names):
                split = path
        self.assertIsNotNone(base, "no APK with classes.dex found")
        return base, split

    def test_entries_match_zipfile(self):
        self.assertGreaterEqual(len(self.cands), 1)
        for path in self.zips():
            with zipio.ZipIn(path) as z, zipfile.ZipFile(path) as zf:
                infos = zf.infolist()
                self.assertEqual(len(z.entries), len(infos))
                self.assertEqual(z.names(), [i.filename for i in infos])
                for e, i in zip(z.entries, infos):
                    self.assertEqual((e.crc32, e.compressed_size, e.size, e.method),
                                     (i.CRC, i.compress_size, i.file_size, i.compress_type))

    def test_stored_data_offsets(self):
        base, _ = self.base_and_split()
        with zipio.ZipIn(base) as z, zipfile.ZipFile(base) as zf:
            stored = [e for e in z.entries if e.method == zipio.METHOD_STORED and e.size]
            self.assertTrue(stored)
            step = max(1, len(stored) // 40)
            picks = stored[::step] + [e for e in stored
                                      if e.name == "resources.arsc" or e.name.endswith(".dex")]
            for e in picks:
                self.assertEqual(z.read_raw(e), zf.read(e.name), e.name)
            deflated = [e for e in z.entries if e.method == zipio.METHOD_DEFLATE][:20]
            for e in deflated:
                self.assertEqual(z.read(e), zf.read(e.name), e.name)

    def test_merged_raw_copy_is_valid(self):
        base, split = self.base_and_split()
        out = os.path.join(self.tmp, "merged-unsigned.apk")
        with zipio.ZipIn(base) as zb:
            plan = zipio.ZipPlan()
            for e in zb.entries:
                plan.add_raw(zb, e)
            if split:
                with zipio.ZipIn(split) as zs:
                    for e in zs.entries:
                        if e.name.startswith("lib/"):
                            plan.add_raw(zs, e)
                    t0 = time.monotonic()
                    summary = zipio.write(plan, out)
                    elapsed = time.monotonic() - t0
            else:
                t0 = time.monotonic()
                summary = zipio.write(plan, out)
                elapsed = time.monotonic() - t0
        self.assertEqual(summary["bytes"], os.path.getsize(out))
        self.assertLess(elapsed, 120)
        with zipfile.ZipFile(out) as zf:
            self.assertIsNone(zf.testzip())
            self.assertEqual(len(zf.infolist()), summary["entries"])
        self.assertEqual(zipio.verify_alignment(out), [])
        os.unlink(out)

    @unittest.skipUnless(_axml_available(), "sgmo_patcher.axml.manifest_info not available")
    def test_full_load(self):
        with inputs.load([SGMO_APK], tmp_dir=self.tmp) as s:
            self.assertEqual(s.package, "com.autumn.skullgirls")
            self.assertEqual(s.version_code, 266)
            self.assertIn("config.arm64_v8a", s.split_names)


if __name__ == "__main__":
    unittest.main()
