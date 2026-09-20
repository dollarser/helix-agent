#!/usr/bin/env python3
"""Shared report and validation contract for Helix acceptance runners.

Provides bounded file loading, identity verification, device lifecycle accounting,
metric validation, and deterministic JSON/Markdown report rendering.
Standard library only.
"""
from dataclasses import dataclass, field
import json
from pathlib import Path
from typing import Any, Dict, List, Optional, Set

SCHEMA_VERSION = 1
MAX_FILE_BYTES = 10 * 1024 * 1024  # 10 MiB
MAX_ENTRIES = 10000


class EvidenceError(ValueError):
    """Raised when evidence fails validation rules."""
    def __init__(self, message: str, exit_code: int = 2):
        super().__init__(message)
        self.exit_code = exit_code


def require(condition: bool, message: str, exit_code: int = 2):
    if not condition:
        raise EvidenceError(message, exit_code=exit_code)


def unique_object_hook(pairs: List[tuple]) -> Dict[str, Any]:
    res: Dict[str, Any] = {}
    for k, v in pairs:
        if k in res:
            raise EvidenceError(f"Duplicate JSON key: {k}", exit_code=2)
        res[k] = v
    return res


def safe_resolve_path(path_str: str, base_dir: Optional[Path] = None) -> Path:
    p = Path(path_str)
    if not p.is_absolute() and base_dir is not None:
        p = (base_dir / p).resolve()
    else:
        p = p.resolve()
    if base_dir is not None:
        base_resolved = base_dir.resolve()
        try:
            p.relative_to(base_resolved)
        except ValueError:
            raise EvidenceError(f"Path escape detected: {path_str} escapes {base_dir}", exit_code=2)
    return p


def safe_load_json(file_path: Path, base_dir: Optional[Path] = None, max_bytes: int = MAX_FILE_BYTES) -> Dict[str, Any]:
    resolved = safe_resolve_path(str(file_path), base_dir=base_dir)
    if not resolved.is_file():
        raise EvidenceError(f"Evidence file not found: {resolved}", exit_code=2)
    size = resolved.stat().st_size
    if size > max_bytes:
        raise EvidenceError(f"File size {size} exceeds limit of {max_bytes} bytes: {resolved}", exit_code=2)
    with resolved.open("rb") as f:
        raw = f.read()
    try:
        data = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=unique_object_hook,
            parse_constant=lambda val: (_ for _ in ()).throw(EvidenceError(f"Invalid constant: {val}", exit_code=2)),
        )
    except Exception as e:
        if isinstance(e, EvidenceError):
            raise
        raise EvidenceError(f"Failed to parse JSON {resolved}: {e}", exit_code=2)
    if not isinstance(data, dict):
        raise EvidenceError(f"Top-level JSON in {resolved} must be an object", exit_code=2)
    return data


@dataclass
class BatchIdentity:
    schema_version: int
    scope: str
    commit_sha: str
    mode: str  # "real" or "fixture"
    api: Optional[int] = None
    flavor: Optional[str] = None
    app_apk_sha256: Optional[str] = None
    test_apk_sha256: Optional[str] = None

    def validate(self):
        require(self.schema_version == SCHEMA_VERSION, f"Unsupported schema version: {self.schema_version}")
        require(bool(self.scope and self.scope.strip()), "Missing or empty scope")
        require(bool(self.commit_sha and len(self.commit_sha.strip()) >= 7), "Invalid or missing commit_sha")
        require(self.mode in ("real", "fixture"), f"Mode must be 'real' or 'fixture', got: {self.mode}")
        if self.mode == "real":
            require(bool(self.app_apk_sha256), "Real mode requires app_apk_sha256")
            require(bool(self.test_apk_sha256), "Real mode requires test_apk_sha256")


@dataclass
class DeviceLifecycle:
    owner_pid: Optional[int] = None
    closed: bool = False
    details: Dict[str, Any] = field(default_factory=dict)

    def validate(self, mode: str):
        if mode == "real":
            require(self.owner_pid is not None and self.owner_pid > 0, "Real mode requires valid owner_pid")
            require(self.closed is True, "Real mode requires closed=True for owned runner lifecycle")


@dataclass
class SceneRecord:
    scene_id: str
    test_class: str
    test_method: str
    status: str  # "passed", "failed", "skipped"
    source_ref: str
    skip_reason: Optional[str] = None
    metrics: Dict[str, Any] = field(default_factory=dict)

    def validate(self):
        require(bool(self.scene_id), "scene_id cannot be empty")
        require(bool(self.test_class), f"test_class missing for scene {self.scene_id}")
        require(bool(self.test_method), f"test_method missing for scene {self.scene_id}")
        require(self.status in ("passed", "failed", "skipped"), f"Invalid scene status: {self.status}")
        if self.status == "skipped":
            require(bool(self.skip_reason and self.skip_reason.strip()), f"Skipped scene {self.scene_id} requires non-empty skip_reason")


@dataclass
class TestCounts:
    expected: int
    executed: int
    passed: int
    failed: int
    skipped: int

    def validate(self):
        require(self.expected >= 0, "expected count must be non-negative")
        require(self.executed >= 0, "executed count must be non-negative")
        require(self.passed >= 0, "passed count must be non-negative")
        require(self.failed >= 0, "failed count must be non-negative")
        require(self.skipped >= 0, "skipped count must be non-negative")
        require(self.executed > 0, "Zero test executions detected; cannot validate acceptance")
        require(
            self.executed == self.passed + self.failed + self.skipped,
            f"Count mismatch: executed ({self.executed}) != passed ({self.passed}) + failed ({self.failed}) + skipped ({self.skipped})",
        )
        require(
            self.expected >= self.executed,
            f"Count mismatch: expected ({self.expected}) < executed ({self.executed})",
        )


def build_markdown_report(
    scope: str,
    identity: BatchIdentity,
    lifecycle: DeviceLifecycle,
    counts: TestCounts,
    scenes: List[SceneRecord],
    verdict: str,
    mandatory_scenes: Set[str],
    missing_scenes: Set[str],
    extra_sections: Optional[Dict[str, str]] = None,
) -> str:
    lines = [
        f"# {scope} Acceptance Report",
        "",
        f"- **Verdict**: `{verdict}`",
        f"- **Schema Version**: {identity.schema_version}",
        f"- **Mode**: `{identity.mode}`" + (" *(Fixture validation only; does not close product acceptance)*" if identity.mode == "fixture" else ""),
        f"- **Commit SHA**: `{identity.commit_sha}`",
        f"- **API**: {identity.api if identity.api is not None else 'N/A'}",
        f"- **Flavor**: {identity.flavor if identity.flavor is not None else 'N/A'}",
        f"- **App APK SHA-256**: `{identity.app_apk_sha256 or 'N/A'}`",
        f"- **Test APK SHA-256**: `{identity.test_apk_sha256 or 'N/A'}`",
        "",
        "## 1. Test Summary",
        "",
        "| Metric | Count |",
        "| --- | --- |",
        f"| Expected | {counts.expected} |",
        f"| Executed | {counts.executed} |",
        f"| Passed | {counts.passed} |",
        f"| Failed | {counts.failed} |",
        f"| Skipped | {counts.skipped} |",
        "",
        "## 2. Device Lifecycle",
        "",
        f"- **Owner PID**: {lifecycle.owner_pid if lifecycle.owner_pid is not None else 'N/A'}",
        f"- **Closed**: `{lifecycle.closed}`",
        "",
        "## 3. Scenes",
        "",
        "| Scene ID | Class::Method | Status | Metrics / Skip Reason |",
        "| --- | --- | --- | --- |",
    ]
    for s in scenes:
        detail = s.skip_reason if s.status == "skipped" else (json.dumps(s.metrics, sort_keys=True) if s.metrics else "-")
        lines.append(f"| `{s.scene_id}` | `{s.test_class}::{s.test_method}` | **{s.status.upper()}** | {detail} |")

    if missing_scenes:
        lines.extend([
            "",
            "## Missing Mandatory Scenes",
            "",
        ])
        for ms in sorted(missing_scenes):
            lines.append(f"- ❌ `{ms}`")

    if extra_sections:
        for title, content in extra_sections.items():
            lines.extend(["", f"## {title}", "", content])

    lines.append("")
    return "\n".join(lines)
