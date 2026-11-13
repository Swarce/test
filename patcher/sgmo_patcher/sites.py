"""Verified-build fingerprints: ``sites/<versionCode>.json``.

A sites file pins the one build the patcher was validated against, so it refuses to touch a
different or modified APK. It records whole-file hashes (manifest, metadata, dex) and the PairIP
gate method fingerprints. It is data (hashes, offsets, short identifiers), never game code.
"""

from __future__ import annotations

import json
from pathlib import Path
from typing import Optional

_DIR = Path(__file__).resolve().parent / "sites"


class SitesError(Exception):
    pass


def sites_path(version_code: int) -> Path:
    return _DIR / f"{version_code}.json"


def load_sites(version_code: int) -> Optional[dict]:
    """Return the sites dict for ``version_code``, or None if there is no file for it."""
    p = sites_path(version_code)
    if not p.exists():
        return None
    try:
        return json.loads(p.read_text())
    except ValueError as e:
        raise SitesError(f"{p} is not valid JSON: {e}") from e
