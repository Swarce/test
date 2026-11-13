"""Python wrappers around the Java helper's dex commands.

These functions hand dex bytes to the helper via temporary files and return parsed
results. They never load game data into the repo; callers pass bytes they read from the
player's own APK.

* :func:`dex_methods` -- per-method fingerprints (raw and semantic).
* :func:`dex_patch` -- replace named methods with safe stubs after a fingerprint check.
* :func:`dex_compare` -- structural + semantic equality except for excluded methods.
"""

from __future__ import annotations

import json
import tempfile
from pathlib import Path
from typing import Iterable, Sequence

from . import javahelper


def dex_methods(dex_bytes: bytes) -> list[dict]:
    """Return one record per coded method: ``cls, name, desc, flags, rawSha256,
    semSha256, registers, ins``."""
    with tempfile.TemporaryDirectory() as d:
        in_dex = Path(d) / "in.dex"
        in_dex.write_bytes(dex_bytes)
        result = javahelper.run(["dex-methods", str(in_dex)])
        return result.json_lines()


def dex_patch(dex_bytes: bytes, sites: Sequence[dict]) -> tuple[bytes | None, list[dict]]:
    """Patch ``dex_bytes`` at the given sites.

    ``sites`` is a list of dicts with keys ``cls, name, desc, expectRawSha256, stub``
    (one of ``RETURN_VOID``/``RETURN_NULL``/``SUPER_CALL``/``SUPER_CALL_THEN_FINISH``),
    optional ``superCls`` (required for the ``SUPER_*`` stubs) and ``required``.

    Returns ``(out_bytes, results)``. ``out_bytes`` is ``None`` when a required site
    mismatched or was missing (the helper exits 2 and writes nothing); ``results`` is the
    per-site status list either way.
    """
    with tempfile.TemporaryDirectory() as d:
        in_dex = Path(d) / "in.dex"
        out_dex = Path(d) / "out.dex"
        spec = Path(d) / "spec.json"
        in_dex.write_bytes(dex_bytes)
        spec.write_text(json.dumps({"sites": list(sites)}))
        result = javahelper.run(
            ["dex-patch", str(in_dex), str(out_dex), str(spec)],
            allowed_returncodes=(0, 2))
        parsed = result.json()
        site_results = parsed.get("sites", [])
        if result.returncode == 2 or not out_dex.exists():
            return None, site_results
        return out_dex.read_bytes(), site_results


def dex_compare(a_bytes: bytes, b_bytes: bytes,
                exclude: Iterable[dict] = ()) -> tuple[bool, list[str]]:
    """Compare two dex files, ignoring the excluded methods.

    ``exclude`` is an iterable of ``{cls, name, desc}`` dicts. Returns ``(ok, differences)``.
    """
    with tempfile.TemporaryDirectory() as d:
        a = Path(d) / "a.dex"
        b = Path(d) / "b.dex"
        excl = Path(d) / "exclude.json"
        a.write_bytes(a_bytes)
        b.write_bytes(b_bytes)
        excl.write_text(json.dumps(list(exclude)))
        result = javahelper.run(
            ["dex-compare", str(a), str(b), str(excl)],
            allowed_returncodes=(0, 3))
        parsed = result.json()
        return bool(parsed.get("ok")), list(parsed.get("differences", []))


def make_test_dex() -> bytes:
    """Return the helper's synthetic test dex (an Activity/Receiver plus untouched methods)."""
    with tempfile.TemporaryDirectory() as d:
        out = Path(d) / "test.dex"
        javahelper.run(["make-test-dex", str(out)])
        return out.read_bytes()


def dex_inject_call(dex_bytes: bytes, *, cls: str, name: str, desc: str,
                    expect_raw_sha256: str | None, invoke: str,
                    required: bool = True) -> tuple[bytes | None, dict]:
    """Prepend ``invoke-static {}, <invoke>`` to one fingerprinted method.

    ``invoke`` is ``"Lowner;->method()V"`` (a void, zero-argument static). Returns
    ``(out_bytes, result)``; ``out_bytes`` is None if a required site mismatched/was missing.
    """
    with tempfile.TemporaryDirectory() as d:
        in_dex = Path(d) / "in.dex"
        out_dex = Path(d) / "out.dex"
        spec = Path(d) / "spec.json"
        in_dex.write_bytes(dex_bytes)
        spec.write_text(json.dumps({
            "cls": cls, "name": name, "desc": desc,
            "expectRawSha256": expect_raw_sha256, "invoke": invoke, "required": required,
        }))
        result = javahelper.run(["dex-inject-call", str(in_dex), str(out_dex), str(spec)],
                                allowed_returncodes=(0, 2))
        parsed = result.json()
        if result.returncode == 2 or parsed.get("status") in ("mismatch", "missing") or not out_dex.exists():
            return None, parsed
        return out_dex.read_bytes(), parsed


def dex_edit(dex_bytes: bytes, ops) -> tuple[bytes | None, list[dict]]:
    """Apply several method edits (stubs and leading-call injections) to one dex in a single pass.

    ``ops`` is a list of dicts: ``{kind: "stub"|"inject", cls, name, desc, expectRawSha256,
    stub?, superCls?, invoke?, required?}``. Fingerprints are checked against the input dex before
    anything is written. Returns ``(out_bytes, results)``; ``out_bytes`` is None if a required site
    mismatched or was missing.
    """
    with tempfile.TemporaryDirectory() as d:
        in_dex = Path(d) / "in.dex"
        out_dex = Path(d) / "out.dex"
        spec = Path(d) / "spec.json"
        in_dex.write_bytes(dex_bytes)
        spec.write_text(json.dumps({"ops": list(ops)}))
        result = javahelper.run(["dex-edit", str(in_dex), str(out_dex), str(spec)],
                                allowed_returncodes=(0, 2))
        parsed = result.json()
        if not parsed.get("ok") or not out_dex.exists():
            return None, parsed.get("sites", [])
        return out_dex.read_bytes(), parsed.get("sites", [])
