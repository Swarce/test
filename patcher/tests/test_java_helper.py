"""Tests for the Java helper and its Python wrappers.

Unit tests use synthetic inputs (a dex built by the helper, a tiny hand-built zip-apk) and
skip cleanly when no JDK >= 17 is available. The real-file tests read the player's own XAPK
from ``SGMO_APK`` and skip without it; they never read game data from the repo.

Run with::

    cd patcher && python -m unittest discover -s tests -v
"""

import os
import unittest
import zipfile
from pathlib import Path


def _java_ok() -> bool:
    try:
        from sgmo_patcher import javahelper
        return javahelper.java_version() >= 17
    except Exception:
        return False


_JAVA_OK = _java_ok()
_SGMO_APK = os.environ.get("SGMO_APK")


# ---- loading real dex files from the player's own package -------------------

def _base_apk_bytes(path: Path) -> bytes:
    """Return the base APK's bytes from an XAPK / .apks / folder / base.apk at ``path``."""
    if path.is_dir():
        for name in ("com.autumn.skullgirls.apk", "base.apk"):
            cand = path / name
            if cand.exists():
                return cand.read_bytes()
        # Any apk that looks like a base (has classes.dex).
        for cand in sorted(path.glob("*.apk")):
            with zipfile.ZipFile(cand) as z:
                if "classes.dex" in z.namelist():
                    return cand.read_bytes()
        raise FileNotFoundError(f"no base apk under {path}")

    # A zip container (XAPK/.apks) or the base apk itself.
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        if "classes.dex" in names:
            return path.read_bytes()  # path already is a base apk
        # Find the base split inside the container.
        candidates = [n for n in names if n.endswith(".apk")]
        preferred = [n for n in candidates
                     if "config." not in n and "split" not in n.lower()]
        for n in (preferred + candidates):
            with z.open(n) as f:
                data = f.read()
            with zipfile.ZipFile(__import__("io").BytesIO(data)) as inner:
                if "classes.dex" in inner.namelist():
                    return data
        raise FileNotFoundError(f"no base apk inside {path}")


def _classes_dex(base_apk: bytes) -> dict:
    """Map ``classesN.dex`` -> bytes from a base APK's bytes."""
    import io
    out = {}
    with zipfile.ZipFile(io.BytesIO(base_apk)) as z:
        for n in z.namelist():
            if n.startswith("classes") and n.endswith(".dex") and "/" not in n:
                out[n] = z.read(n)
    return out


@unittest.skipUnless(_JAVA_OK, "needs java/javac >= 17")
class SelfTestCase(unittest.TestCase):
    """The helper's own in-memory self-test exercises every stub and the checks."""

    def test_selftest(self):
        from sgmo_patcher import javahelper
        result = javahelper.selftest()
        self.assertEqual(result.returncode, 0)
        self.assertTrue(result.json().get("ok"))


@unittest.skipUnless(_JAVA_OK, "needs java/javac >= 17")
class SyntheticDexTests(unittest.TestCase):
    """dex-methods / dex-patch / dex-compare on a synthetic dex built by the helper."""

    @classmethod
    def setUpClass(cls):
        from sgmo_patcher import dex
        cls.dex_bytes = dex.make_test_dex()
        cls.methods = {
            (m["cls"], m["name"], m["desc"]): m
            for m in dex.dex_methods(cls.dex_bytes)
        }

    def test_methods_have_fingerprints(self):
        # Every coded method has both fingerprints and sane counts.
        for key, m in self.methods.items():
            self.assertRegex(m["rawSha256"], r"^[0-9a-f]{64}$", key)
            self.assertRegex(m["semSha256"], r"^[0-9a-f]{64}$", key)
            self.assertGreaterEqual(m["registers"], m["ins"])

        # The PairIP-style onCreate is present and calls the static VMRunner stand-in.
        oncreate = self.methods[("Lsgmo/synthetic/MyActivity;", "onCreate",
                                 "(Landroid/os/Bundle;)V")]
        self.assertEqual(oncreate["ins"], 2)  # this + Bundle

    def test_patch_all_stubs_then_compare(self):
        from sgmo_patcher import dex

        def raw(cls, name, desc):
            return self.methods[(cls, name, desc)]["rawSha256"]

        A = "Lsgmo/synthetic/MyActivity;"
        R = "Lsgmo/synthetic/MyReceiver;"
        sites = [
            {"cls": A, "name": "onCreate", "desc": "(Landroid/os/Bundle;)V",
             "expectRawSha256": raw(A, "onCreate", "(Landroid/os/Bundle;)V"),
             "stub": "SUPER_CALL", "superCls": "Landroid/app/Activity;", "required": True},
            {"cls": A, "name": "onDestroy", "desc": "()V",
             "expectRawSha256": raw(A, "onDestroy", "()V"),
             "stub": "SUPER_CALL_THEN_FINISH", "superCls": "Landroid/app/Activity;",
             "required": True},
            {"cls": A, "name": "getThing", "desc": "()Ljava/lang/Object;",
             "expectRawSha256": raw(A, "getThing", "()Ljava/lang/Object;"),
             "stub": "RETURN_NULL", "required": True},
            {"cls": R, "name": "onReceive",
             "desc": "(Landroid/content/Context;Landroid/content/Intent;)V",
             "expectRawSha256": raw(R, "onReceive",
                                    "(Landroid/content/Context;Landroid/content/Intent;)V"),
             "stub": "RETURN_VOID", "required": True},
        ]
        out_bytes, results = dex.dex_patch(self.dex_bytes, sites)
        self.assertIsNotNone(out_bytes)
        for r in results:
            self.assertEqual(r["status"], "patched", r)

        exclude = [{"cls": s["cls"], "name": s["name"], "desc": s["desc"]} for s in sites]
        ok, diffs = dex.dex_compare(self.dex_bytes, out_bytes, exclude)
        self.assertTrue(ok, diffs)

        # Without excludes, the differences must show up.
        ok2, diffs2 = dex.dex_compare(self.dex_bytes, out_bytes, [])
        self.assertFalse(ok2)
        self.assertTrue(diffs2)

        # The patched methods really changed; untouched ones did not.
        new = {(m["cls"], m["name"], m["desc"]): m for m in dex.dex_methods(out_bytes)}
        self.assertNotEqual(
            new[(A, "getThing", "()Ljava/lang/Object;")]["semSha256"],
            self.methods[(A, "getThing", "()Ljava/lang/Object;")]["semSha256"])
        self.assertEqual(
            new[("Lsgmo/synthetic/Untouched;", "switchy", "()V")]["semSha256"],
            self.methods[("Lsgmo/synthetic/Untouched;", "switchy", "()V")]["semSha256"])

    def test_required_mismatch_writes_nothing(self):
        from sgmo_patcher import dex
        sites = [{
            "cls": "Lsgmo/synthetic/MyActivity;", "name": "onCreate",
            "desc": "(Landroid/os/Bundle;)V",
            "expectRawSha256": "00" * 32, "stub": "RETURN_VOID", "required": True,
        }]
        out_bytes, results = dex.dex_patch(self.dex_bytes, sites)
        self.assertIsNone(out_bytes)
        self.assertEqual(results[0]["status"], "mismatch")

    def test_optional_mismatch_keeps_original(self):
        from sgmo_patcher import dex
        sites = [{
            "cls": "Lsgmo/synthetic/MyActivity;", "name": "onCreate",
            "desc": "(Landroid/os/Bundle;)V",
            "expectRawSha256": "00" * 32, "stub": "RETURN_VOID", "required": False,
        }]
        out_bytes, results = dex.dex_patch(self.dex_bytes, sites)
        self.assertIsNotNone(out_bytes)
        self.assertEqual(results[0]["status"], "mismatch")


@unittest.skipUnless(_JAVA_OK, "needs java/javac >= 17")
class SignVerifyTests(unittest.TestCase):
    """keygen + sign a synthetic zip-apk + verify, with an isolated keystore."""

    def setUp(self):
        import tempfile
        self._dir = Path(tempfile.mkdtemp(prefix="sgmo-sign-"))
        self._old_env = {
            k: os.environ.get(k) for k in ("SGMO_KEYSTORE", "SGMO_KEYSTORE_PASS_FILE")
        }
        os.environ["SGMO_KEYSTORE"] = str(self._dir / "k.p12")
        os.environ["SGMO_KEYSTORE_PASS_FILE"] = str(self._dir / "k.pass")

    def tearDown(self):
        for k, v in self._old_env.items():
            if v is None:
                os.environ.pop(k, None)
            else:
                os.environ[k] = v
        import shutil
        shutil.rmtree(self._dir, ignore_errors=True)

    def _synthetic_apk(self) -> Path:
        apk = self._dir / "unsigned.apk"
        with zipfile.ZipFile(apk, "w") as z:
            z.writestr("AndroidManifest.xml", b"\x03\x00\x08\x00synthetic-manifest")
            zi = zipfile.ZipInfo("lib/arm64-v8a/libmain.so")
            zi.compress_type = zipfile.ZIP_STORED
            z.writestr(zi, b"\x7fELF" + b"A" * 5000)
            z.writestr("classes.dex", b"dex\n035\x00" + b"\x00" * 64)
            zi2 = zipfile.ZipInfo("resources.arsc")
            zi2.compress_type = zipfile.ZIP_STORED
            z.writestr(zi2, b"B" * 300)
        return apk

    def test_keygen_refuses_overwrite(self):
        from sgmo_patcher import sign
        ks = sign.keygen()
        self.assertTrue(ks.exists())
        with self.assertRaises(FileExistsError):
            sign.keygen()

    def test_sign_and_verify(self):
        from sgmo_patcher import sign
        sign.keygen()
        cert = sign.keystore_cert_sha256()
        self.assertRegex(cert, r"^[0-9a-f]{64}$")

        apk = self._synthetic_apk()

        # Below API 24, v1 (JAR) signing is added and all three schemes verify.
        out21 = self._dir / "signed21.apk"
        r21 = sign.sign(apk, out21, min_sdk=21)
        self.assertEqual(r21["certSha256"], cert)
        self.assertEqual(r21["schemes"], ["v1", "v2", "v3"])
        v21 = sign.verify(out21, min_sdk=21)
        self.assertTrue(v21["verified"])
        self.assertTrue(v21["v1"] and v21["v2"] and v21["v3"], v21)
        self.assertIn(cert, v21["certSha256"])

        # At min-sdk 24 (the target), v2+v3 only: no v1, and the reported schemes match.
        out24 = self._dir / "signed24.apk"
        r24 = sign.sign(apk, out24, min_sdk=24)
        self.assertEqual(r24["schemes"], ["v2", "v3"])
        v24 = sign.verify(out24, min_sdk=24)
        self.assertTrue(v24["verified"])
        self.assertFalse(v24["v1"])
        self.assertTrue(v24["v2"] and v24["v3"], v24)
        self.assertNotIn("META-INF/SGM-OFFL.SF", set(zipfile.ZipFile(out24).namelist()))

        # Stored entries are aligned by apksig: 4 bytes, and .so to 16 KiB.
        self._assert_alignment(out24)

    def _assert_alignment(self, apk: Path):
        import struct
        data = apk.read_bytes()
        with zipfile.ZipFile(apk) as z:
            for info in z.infolist():
                if info.compress_type != zipfile.ZIP_STORED:
                    continue
                ho = info.header_offset
                n = struct.unpack("<H", data[ho + 26:ho + 28])[0]
                e = struct.unpack("<H", data[ho + 28:ho + 30])[0]
                data_off = ho + 30 + n + e
                mult = 16384 if info.filename.endswith(".so") else 4
                self.assertEqual(data_off % mult, 0,
                                 f"{info.filename} not aligned to {mult}")


@unittest.skipUnless(_JAVA_OK, "needs java/javac >= 17")
@unittest.skipUnless(_SGMO_APK, "set SGMO_APK to run real-file tests")
class RealApkTests(unittest.TestCase):
    """dex-methods / dex-patch / dex-compare against the player's real base APK."""

    @classmethod
    def setUpClass(cls):
        cls.base = _base_apk_bytes(Path(_SGMO_APK))
        cls.dexes = _classes_dex(cls.base)
        assert cls.dexes, "no classes*.dex found in the base APK"

    def test_dex_methods_each_dex(self):
        from sgmo_patcher import dex
        try:
            import androguard  # noqa: F401
            have_androguard = True
        except Exception:
            have_androguard = False

        found_attach = False
        for name, data in sorted(self.dexes.items()):
            methods = dex.dex_methods(data)
            self.assertGreaterEqual(len(methods), 200, name)
            by_key = {(m["cls"], m["name"], m["desc"]): m for m in methods}

            attach = ("Lcom/pairip/application/Application;", "attachBaseContext",
                      "(Landroid/content/Context;)V")
            if attach in by_key:
                found_attach = True
                self.assertRegex(by_key[attach]["rawSha256"], r"^[0-9a-f]{64}$")

            if have_androguard:
                self._cross_check_androguard(data, by_key, name)

        self.assertTrue(found_attach,
                        "Application.attachBaseContext not found in any dex")

    def _cross_check_androguard(self, data: bytes, by_key: dict, name: str):
        import hashlib
        import loguru
        loguru.logger.remove()
        from androguard.core.dex import DEX

        d = DEX(data)
        checked = 0
        for c in d.get_classes():
            for m in c.get_methods():
                code = m.get_code()
                if not code:
                    continue
                key = (c.get_name(), m.get_name(),
                       m.get_descriptor().replace(" ", ""))
                if key not in by_key:
                    continue
                expected = hashlib.sha256(bytes(code.get_bc().get_raw())).hexdigest()
                self.assertEqual(by_key[key]["rawSha256"], expected,
                                 f"{name}: {key} rawSha256 != androguard")
                checked += 1
        self.assertGreaterEqual(checked, 200,
                                f"{name}: only cross-checked {checked} methods")

    def test_patch_pairip_clinit_and_compare(self):
        from sgmo_patcher import dex
        # Application lives in classes2.dex for this build, but find it regardless.
        target = None
        for name, data in self.dexes.items():
            by_key = {(m["cls"], m["name"], m["desc"]): m for m in dex.dex_methods(data)}
            key = ("Lcom/pairip/application/Application;", "<clinit>", "()V")
            if key in by_key:
                target = (data, by_key[key])
                break
        self.assertIsNotNone(target, "Application.<clinit> not found")
        data, method = target

        sites = [{
            "cls": "Lcom/pairip/application/Application;", "name": "<clinit>", "desc": "()V",
            "expectRawSha256": method["rawSha256"], "stub": "RETURN_VOID", "required": True,
        }]
        out_bytes, results = dex.dex_patch(data, sites)
        self.assertIsNotNone(out_bytes)
        self.assertEqual(results[0]["status"], "patched")

        exclude = [{"cls": "Lcom/pairip/application/Application;",
                    "name": "<clinit>", "desc": "()V"}]
        ok, diffs = dex.dex_compare(data, out_bytes, exclude)
        self.assertTrue(ok, diffs)


if __name__ == "__main__":
    unittest.main()
