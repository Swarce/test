"""End-to-end patcher test against the player's real copy.

Skips unless SGMO_APK points at a Skullgirls 8.12.0 XAPK/.apks/folder. Runs the whole pipeline
into a temp dir with a throwaway keystore, then checks the output: it is signed and aligned, the
game-API literal is redirected, the CDN literal is untouched, the manifest split requirement is
cleared, classes2.dex differs only in the two stubbed PairIP gates, and the other dex files are
byte-identical to the input.
"""

from __future__ import annotations

import io
import os
import tempfile
import unittest
import zipfile
from pathlib import Path

import sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

APK = os.environ.get("SGMO_APK")


def _have_java() -> bool:
    import shutil
    return shutil.which("java") is not None and shutil.which("javac") is not None


@unittest.skipUnless(APK and os.path.exists(APK), "SGMO_APK not set")
@unittest.skipUnless(_have_java(), "no JDK on PATH")
class PatchRealTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        from sgmo_patcher import patcher, sign
        cls.tmp = tempfile.mkdtemp()
        ks = Path(cls.tmp) / "k.p12"
        pf = Path(cls.tmp) / "k.pass"
        sign.keygen(ks, pf)
        cls.out = Path(cls.tmp) / "out.apk"
        cls.report = patcher.patch(
            [APK], cls.out,
            patcher.PatchOptions(port=47820, keystore=ks, pass_file=pf))

    @classmethod
    def tearDownClass(cls):
        import shutil
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def test_report_ok(self):
        self.assertTrue(self.report.ok, self.report.to_text())
        self.assertEqual(self.report.version_code, 266)

    def test_signed_and_aligned(self):
        from sgmo_patcher import sign, zipio
        v = sign.verify(self.out)
        self.assertTrue(v["verified"])
        self.assertTrue(v["v2"] and v["v3"])
        self.assertEqual(zipio.verify_alignment(self.out), [])

    def test_api_redirected_cdn_untouched(self):
        from sgmo_patcher import metadata
        z = zipfile.ZipFile(self.out)
        md = metadata.Metadata.parse(
            z.read("assets/bin/Data/Managed/Metadata/global-metadata.dat"))
        self.assertEqual(md.literal_str(19449), "http://127.0.0.1:47820")
        self.assertEqual(md.literal_str(19448), "https://skgm-prod-cdn.skullgirlsmobile.com/")

    def test_manifest_split_requirement_cleared(self):
        from sgmo_patcher import axml
        z = zipfile.ZipFile(self.out)
        info = axml.manifest_info(z.read("AndroidManifest.xml"))
        self.assertIn(info.get("requiredSplitTypes"), ("", None))

    def test_libs_merged_and_pairipcore_kept(self):
        z = zipfile.ZipFile(self.out)
        names = set(z.namelist())
        self.assertIn("lib/arm64-v8a/libil2cpp.so", names)
        self.assertIn("lib/arm64-v8a/libpairipcore.so", names)  # must NOT be removed
        self.assertFalse([n for n in names if n.startswith("META-INF/") and n.endswith(".RSA")
                          and "SGM" not in n])

    def test_only_two_methods_changed_in_classes2(self):
        from sgmo_patcher import dex, inputs, axml
        with inputs.load([APK], manifest_reader=axml.manifest_info) as apk:
            orig = {n: apk.base.read(n) for n in apk.base.names()
                    if n.startswith("classes") and n.endswith(".dex")}
        z = zipfile.ZipFile(self.out)
        gates = [
            {"cls": "Lcom/pairip/SignatureCheck;", "name": "verifyIntegrity",
             "desc": "(Landroid/content/Context;)V"},
            {"cls": "Lcom/pairip/licensecheck/LicenseClient;", "name": "checkLicense",
             "desc": "(Landroid/content/Context;)V"},
        ]
        for name, before in orig.items():
            after = z.read(name)
            if name == "classes2.dex":
                ok, diffs = dex.dex_compare(before, after, gates)
                self.assertTrue(ok, f"classes2.dex changed beyond the gates: {diffs[:3]}")
                self.assertNotEqual(before, after)
            else:
                # other dexes are copied verbatim
                self.assertEqual(before, after, f"{name} should be unchanged")


if __name__ == "__main__":
    unittest.main()
