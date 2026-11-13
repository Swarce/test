"""Unit tests for sgmo_patcher.axml.

Synthetic manifests are built in both a UTF-8 and a UTF-16 string pool by axml_builder; the
real-file tests read the player's own XAPK through SGMO_APK and skip cleanly without it.
"""

import io
import os
import sys
import unittest
import zipfile

_HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(_HERE, ".."))
sys.path.insert(0, _HERE)

import axml_builder  # noqa: E402
from axml_builder import ANDROID_NS, build_sample_manifest  # noqa: E402
from sgmo_patcher import axml  # noqa: E402
from sgmo_patcher.axml import AxmlDoc, AxmlError, manifest_info  # noqa: E402

_APK = os.environ.get("SGMO_APK")
_HAS_APK = bool(_APK) and os.path.exists(_APK or "")


def changed_lines(diff):
    """The real +/- lines of a unified diff (not the ---/+++ file headers or @@ hunks)."""
    return [ln for ln in diff
            if (ln.startswith("+") and not ln.startswith("+++"))
            or (ln.startswith("-") and not ln.startswith("---"))]


def load_base_and_splits(path):
    """From an XAPK (a zip of apks), return (base_apk_bytes, {split_name: split_bytes})."""
    with zipfile.ZipFile(path) as z:
        apks = [n for n in z.namelist() if n.endswith(".apk")]
        if not apks:  # path may itself be a plain apk
            return None, {}
        base_name = next((n for n in apks if "config" not in n and "split" not in n), apks[0])
        base = z.read(base_name)
        splits = {n: z.read(n) for n in apks if n != base_name}
    return base, splits


def apk_entry(apk_bytes, name):
    with zipfile.ZipFile(io.BytesIO(apk_bytes)) as z:
        return z.read(name)


class SyntheticAxmlTest(unittest.TestCase):
    def test_round_trip_both_pools(self):
        for utf8 in (False, True):
            data = build_sample_manifest(utf8=utf8)
            doc = AxmlDoc.parse(data)
            self.assertEqual(doc.to_bytes(), data, f"round-trip (utf8={utf8})")
            self.assertEqual(doc.utf8, utf8)
            self.assertIn("", doc.strings)
            self.assertIn("base__abi", doc.strings)
            # resource map present and parallel to the first pool strings.
            self.assertTrue(doc.resource_ids)
            vc = doc.attr(doc.find(("manifest",))[0], "versionCode")
            self.assertEqual(vc.resid, 0x0101021B)

    def test_manifest_info_both_pools(self):
        for utf8 in (False, True):
            info = manifest_info(build_sample_manifest(utf8=utf8))
            self.assertEqual(info["package"], "com.example.app")
            self.assertEqual(info["versionCode"], 266)
            self.assertIsInstance(info["versionCode"], int)
            self.assertEqual(info["versionName"], "8.12.0")
            self.assertIsNone(info["split"])
            self.assertEqual(info["requiredSplitTypes"], "base__abi")
            self.assertEqual(info["splitTypes"], "")
            self.assertEqual(info["application_name"], "com.pairip.application.Application")
            self.assertEqual(info["minSdkVersion"], 24)
            self.assertEqual(info["targetSdkVersion"], 33)
            self.assertIs(info["extractNativeLibs"], False)

    def test_find_locates_meta_data(self):
        for utf8 in (False, True):
            doc = AxmlDoc.parse(build_sample_manifest(utf8=utf8))
            found = doc.find(("manifest", "application", "meta-data"),
                             {"name": "com.android.vending.splits.required"})
            self.assertEqual(len(found), 1)
            el = found[0]
            self.assertEqual(el.depth, 2)
            self.assertEqual(el.path, ("manifest", "application", "meta-data"))
            self.assertIs(doc.attr(el, "value").value, True)
            # No match when the filter value is wrong.
            self.assertEqual(doc.find(("manifest", "application", "meta-data"),
                                      {"name": "nope"}), [])

    def test_edit_changes_only_two_lines_and_keeps_length(self):
        for utf8 in (False, True):
            data = build_sample_manifest(utf8=utf8)
            before = AxmlDoc.parse(data)
            after = AxmlDoc.parse(data)

            manifest = after.find(("manifest",))[0]
            rst = after.attr(manifest, "requiredSplitTypes")
            empty_idx = after.find_string("")
            self.assertIsNotNone(empty_idx)
            after.set_attr_string(rst, empty_idx)
            self.assertEqual(rst.value, "")
            self.assertEqual(rst.type, 0x03)

            meta = after.find(("manifest", "application", "meta-data"),
                              {"name": "com.android.vending.splits.required"})[0]
            value = after.attr(meta, "value")
            after.set_attr_typed(value, 0x12, 0)
            self.assertIs(value.value, False)
            self.assertEqual(value.raw_value_idx, -1)

            self.assertEqual(len(after.to_bytes()), len(data), "length preserved")

            diff = changed_lines(axml.diff_xml(before, after))
            self.assertEqual(len(diff), 4, diff)  # two lines, each a -/+ pair
            removed = [ln for ln in diff if ln.startswith("-")]
            added = [ln for ln in diff if ln.startswith("+")]
            self.assertTrue(any('requiredSplitTypes="base__abi"' in ln for ln in removed))
            self.assertTrue(any('requiredSplitTypes=""' in ln for ln in added))
            self.assertTrue(any('android:value="true"' in ln for ln in removed))
            self.assertTrue(any('android:value="false"' in ln for ln in added))

    def test_set_attr_string_out_of_range(self):
        doc = AxmlDoc.parse(build_sample_manifest())
        attr = doc.attr(doc.find(("manifest",))[0], "requiredSplitTypes")
        with self.assertRaises(AxmlError):
            doc.set_attr_string(attr, len(doc.strings))
        with self.assertRaises(AxmlError):
            doc.set_attr_string(attr, -1)

    def test_set_attr_typed_keep_raw(self):
        doc = AxmlDoc.parse(build_sample_manifest())
        attr = doc.attr(doc.find(("manifest",))[0], "versionName")
        raw_before = attr.raw_value_idx
        self.assertNotEqual(raw_before, -1)
        doc.set_attr_typed(attr, 0x10, 999, keep_raw=True)
        self.assertEqual(attr.raw_value_idx, raw_before)
        self.assertEqual(attr.value, 999)

    def test_truncated_and_malformed_raise(self):
        data = build_sample_manifest()
        with self.assertRaises(AxmlError):
            AxmlDoc.parse(data[: len(data) // 2])  # truncated
        with self.assertRaises(AxmlError):
            AxmlDoc.parse(data[:4])  # too small
        with self.assertRaises(AxmlError):
            AxmlDoc.parse(b"\x00\x00" + data[2:])  # wrong top chunk type
        with self.assertRaises(AxmlError):
            AxmlDoc.parse(b"not binary xml at all")

    def test_optional_androguard_cross_check(self):
        try:
            import logging
            logging.disable(logging.CRITICAL)
            try:
                from loguru import logger
                logger.remove()
            except Exception:
                pass
            from androguard.core.axml import AXMLPrinter
        except Exception:
            self.skipTest("androguard not importable")
        for utf8 in (False, True):
            root = AXMLPrinter(build_sample_manifest(utf8=utf8)).get_xml_obj()
            man = root.xpath("//manifest")[0]
            self.assertEqual(man.get("package"), "com.example.app")
            self.assertEqual(man.get("{%s}versionCode" % ANDROID_NS), "266")


@unittest.skipUnless(_HAS_APK, "SGMO_APK is not set to an existing file")
class RealApkAxmlTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.base, cls.splits = load_base_and_splits(_APK)
        if cls.base is None:
            raise unittest.SkipTest("SGMO_APK does not look like an XAPK")
        cls.manifest = apk_entry(cls.base, "AndroidManifest.xml")

    def test_base_manifest_info(self):
        info = manifest_info(self.manifest)
        self.assertEqual(info["package"], "com.autumn.skullgirls")
        self.assertEqual(info["versionCode"], 266)
        self.assertEqual(info["versionName"], "8.12.0")
        self.assertEqual(info["requiredSplitTypes"], "base__abi")
        self.assertEqual(info["application_name"], "com.pairip.application.Application")
        self.assertEqual(info["minSdkVersion"], 24)
        self.assertIn("", AxmlDoc.parse(self.manifest).strings)

    def test_merge_splits_edit_is_two_lines_and_same_length(self):
        before = AxmlDoc.parse(self.manifest)
        after = AxmlDoc.parse(self.manifest)

        manifest = after.find(("manifest",))[0]
        rst = after.attr(manifest, "requiredSplitTypes")
        self.assertIsNotNone(rst)
        empty_idx = after.find_string("")
        self.assertIsNotNone(empty_idx)
        after.set_attr_string(rst, empty_idx)

        meta = after.find(("manifest", "application", "meta-data"),
                          {"name": "com.android.vending.splits.required"})[0]
        after.set_attr_typed(after.attr(meta, "value"), 0x12, 0)

        self.assertEqual(len(after.to_bytes()), len(self.manifest))
        diff = changed_lines(axml.diff_xml(before, after))
        self.assertEqual(len(diff), 4, diff)
        self.assertTrue(any('requiredSplitTypes=""' in ln for ln in diff))
        self.assertTrue(any('android:value="false"' in ln for ln in diff))

    def test_config_split_manifest(self):
        arm = next((b for n, b in self.splits.items() if "arm64" in n), None)
        if arm is None:
            self.skipTest("no arm64 split in this XAPK")
        info = manifest_info(apk_entry(arm, "AndroidManifest.xml"))
        self.assertEqual(info["split"], "config.arm64_v8a")

    def test_optional_androguard_cross_check(self):
        try:
            import logging
            logging.disable(logging.CRITICAL)
            try:
                from loguru import logger
                logger.remove()
            except Exception:
                pass
            from androguard.core.axml import AXMLPrinter
        except Exception:
            self.skipTest("androguard not importable")
        root = AXMLPrinter(self.manifest).get_xml_obj()
        man = root.xpath("//manifest")[0]
        self.assertEqual(man.get("package"), "com.autumn.skullgirls")
        self.assertEqual(man.get("{%s}versionCode" % ANDROID_NS), "266")


if __name__ == "__main__":
    unittest.main()


class RemoveElementsRealTest(unittest.TestCase):
    """Remove <provider> elements from the real manifest and check it stays valid (needs SGMO_APK)."""

    def setUp(self):
        import os, zipfile
        apk = os.environ.get("SGMO_APK")
        if not apk or not os.path.exists(apk):
            self.skipTest("SGMO_APK not set")
        z = zipfile.ZipFile(apk)
        base = [n for n in z.namelist() if n.endswith(".apk") and "config" not in n]
        import io
        inner = zipfile.ZipFile(io.BytesIO(z.read(base[0]))) if base else z
        self.man = inner.read("AndroidManifest.xml")

    def test_remove_one_provider_shrinks_and_reparses(self):
        from sgmo_patcher import axml
        doc = axml.AxmlDoc.parse(self.man)
        provs = [e for e in doc.elements() if e.name == "provider"]
        self.assertTrue(provs, "expected providers in the manifest")
        target = doc.attr(provs[0], "name").value
        out = doc.remove_elements(
            lambda el: el.name == "provider" and doc.attr(el, "name")
            and doc.attr(el, "name").value == target)
        self.assertLess(len(out), len(self.man))
        after = axml.AxmlDoc.parse(out)
        names = {after.attr(e, "name").value for e in after.elements()
                 if e.name == "provider" and after.attr(e, "name")}
        self.assertNotIn(target, names)
        # package and app class unchanged
        info = axml.manifest_info(out)
        self.assertEqual(info["package"], "com.autumn.skullgirls")
        self.assertEqual(info["versionCode"], 266)

    def test_remove_nothing_is_identity(self):
        from sgmo_patcher import axml
        doc = axml.AxmlDoc.parse(self.man)
        out = doc.remove_elements(lambda el: False)
        self.assertEqual(out, self.man)
