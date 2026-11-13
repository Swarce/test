"""The patch report: a JSON record and a human-readable text rendering of a patch run.

Every run writes ``<out>.report.json`` and ``<out>.report.txt`` next to the patched APK.
The report lists the inputs, each patch, each site (expected vs actual fingerprint and the
result), the output verification, and anything removed and why.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any


@dataclass
class SiteRecord:
    """One fingerprinted patch site."""
    kind: str                  # "dex-method" | "metadata-literal" | "manifest-attr" | "file"
    name: str                  # human-readable site id
    status: str                # "applied" | "skipped" | "refused" | "unchanged"
    expected: str | None = None
    actual: str | None = None
    note: str = ""


@dataclass
class PatchRecord:
    """One patch (one concern)."""
    name: str
    description: str
    status: str                # "applied" | "skipped" | "refused" | "off"
    sites: list[SiteRecord] = field(default_factory=list)
    removed: list[dict] = field(default_factory=list)   # [{path, reason}]
    notes: list[str] = field(default_factory=list)


@dataclass
class Report:
    """The whole run."""
    tool_version: str
    package: str = ""
    version_code: int = 0
    version_name: str = ""
    inputs: list[str] = field(default_factory=list)
    output: str = ""
    port: int = 0
    patches: list[PatchRecord] = field(default_factory=list)
    verification: dict = field(default_factory=dict)
    ok: bool = False

    def patch(self, name: str, description: str, status: str = "applied") -> PatchRecord:
        p = PatchRecord(name=name, description=description, status=status)
        self.patches.append(p)
        return p

    def to_json(self) -> str:
        return json.dumps(asdict(self), indent=2, sort_keys=False)

    def to_text(self) -> str:
        L: list[str] = []
        L.append(f"sgm-offline patch report  (tool {self.tool_version})")
        L.append("=" * 60)
        L.append(f"package     {self.package}")
        L.append(f"version     {self.version_name} (versionCode {self.version_code})")
        L.append(f"server port {self.port}")
        for s in self.inputs:
            L.append(f"input       {s}")
        L.append(f"output      {self.output}")
        L.append(f"result      {'OK' if self.ok else 'FAILED'}")
        L.append("")
        for p in self.patches:
            L.append(f"[{p.status.upper()}] {p.name} — {p.description}")
            for note in p.notes:
                L.append(f"    note: {note}")
            for s in p.sites:
                exp = f" expected {_short(s.expected)}" if s.expected else ""
                act = f" actual {_short(s.actual)}" if s.actual else ""
                extra = f"  ({s.note})" if s.note else ""
                L.append(f"    - {s.status:9} {s.kind}: {s.name}{exp}{act}{extra}")
            for r in p.removed:
                L.append(f"    - removed  {r.get('path')}  ({r.get('reason','')})")
            L.append("")
        if self.verification:
            L.append("verification")
            for k, v in self.verification.items():
                L.append(f"    {k}: {v}")
            L.append("")
        return "\n".join(L)

    def write(self, out_apk: Path | str) -> tuple[Path, Path]:
        out_apk = Path(out_apk)
        pj = out_apk.with_name(out_apk.name + ".report.json")
        pt = out_apk.with_name(out_apk.name + ".report.txt")
        pj.write_text(self.to_json())
        pt.write_text(self.to_text())
        return pj, pt


def _short(h: Any) -> str:
    s = str(h)
    return s if len(s) <= 20 else s[:16] + "…"
