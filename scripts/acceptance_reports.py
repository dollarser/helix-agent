#!/usr/bin/env python3
"""Shared report and validation contract for Helix acceptance runners.

Provides bounded file loading, identity verification, device lifecycle accounting,
metric validation, and deterministic JSON/Markdown report rendering.
Standard library only.
"""
from dataclasses import dataclass, field
import json
from pathlib import Path
import re
from typing import Any, Dict, List, Optional, Set

SCHEMA_VERSION = 1
MAX_FILE_BYTES = 10 * 1024 * 1024  # 10 MiB
MAX_ENTRIES = 10000

FAILURE_PATTERNS = [
    re.compile(r"\bFAILURES!!!"),
    re.compile(r"INSTRUMENTATION_RESULT:\s*stream=.*FAILURES", re.IGNORECASE),
    re.compile(r"INSTRUMENTATION_STATUS:\s*failure\b", re.IGNORECASE),
    re.compile(r"INSTRUMENTATION_STATUS_CODE:\s*-[12]\b"),
    re.compile(r"Tests run:\s*\d+,\s*Failures:\s*[1-9]\d*"),
    re.compile(r"Tests run:\s*\d+,\s*Failures:\s*\d+,\s*Errors:\s*[1-9]\d*"),
    re.compile(r"\bProcess crashed\b", re.IGNORECASE),
    re.compile(r"\bFATAL EXCEPTION\b", re.IGNORECASE),
    re.compile(r"\bjava\.lang\.AssertionError\b"),
]


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


def verify_source_evidence(
    source_ref: str,
    base_dir: Path,
    mode: str,
    declared_status: str,
    test_class: str,
    test_method: str,
    metrics: Optional[Dict[str, Any]] = None,
) -> None:
    require(bool(source_ref and source_ref.strip()), f"Empty source_ref for {test_class}::{test_method}", exit_code=2)
    ref_path = safe_resolve_path(source_ref, base_dir=base_dir)
    if not ref_path.is_file():
        if mode == "real":
            raise EvidenceError(f"Evidence file missing in real mode: {ref_path}", exit_code=2)
        return

    size = ref_path.stat().st_size
    require(size <= MAX_FILE_BYTES, f"Evidence file {ref_path} exceeds {MAX_FILE_BYTES} bytes", exit_code=2)
    raw_bytes = ref_path.read_bytes()
    raw_text = raw_bytes.decode("utf-8", errors="replace")

    for pat in FAILURE_PATTERNS:
        match = pat.search(raw_text)
        if match:
            if declared_status == "passed":
                raise EvidenceError(
                    f"Scene {test_class}::{test_method} claims passed but evidence '{source_ref}' contains failure: '{match.group(0)}'",
                    exit_code=2,
                )

    ev_data = None
    if ref_path.suffix == ".json":
        ev_data = safe_load_json(ref_path, base_dir=base_dir)

        if isinstance(ev_data, dict):
            status = str(ev_data.get("status", "")).lower()
            verdict = str(ev_data.get("verdict", "")).lower()
            if status in ("failed", "fail", "error") or verdict in ("failed", "fail", "error"):
                if declared_status == "passed":
                    raise EvidenceError(
                        f"Scene {test_class}::{test_method} claims passed but evidence has status='{status}'/verdict='{verdict}'",
                        exit_code=2,
                    )
            if ev_data.get("failures", 0) > 0 or ev_data.get("errors", 0) > 0:
                if declared_status == "passed":
                    raise EvidenceError(
                        f"Scene {test_class}::{test_method} claims passed but evidence reports failures/errors > 0",
                        exit_code=2,
                    )

            if metrics and isinstance(ev_data.get("metrics"), dict):
                ev_metrics = ev_data["metrics"]
                for k, v in metrics.items():
                    if k in ev_metrics:
                        if isinstance(v, (int, float)) and isinstance(ev_metrics[k], (int, float)):
                            if k.endswith("duration_seconds") and ev_metrics[k] < v:
                                raise EvidenceError(f"Evidence metric {k}={ev_metrics[k]} below required {v}", exit_code=2)
                            elif not k.endswith("duration_seconds") and ev_metrics[k] != v:
                                raise EvidenceError(f"Evidence metric {k}={ev_metrics[k]} does not match required {v}", exit_code=2)

    if mode == "real" and declared_status == "passed":
        if ref_path.suffix == ".json" and isinstance(ev_data, dict):
            require(ev_data.get("test_class") == test_class and ev_data.get("test_method") == test_method,
                    f"Evidence {source_ref} lacks exact class/method identity")
            require(ev_data.get("status") == "passed", f"Evidence {source_ref} lacks passed status")
            raw_ref = ev_data.get("raw_log_ref")
            require(isinstance(raw_ref, str) and bool(raw_ref), f"Evidence {source_ref} lacks raw_log_ref")
            raw_path = safe_resolve_path(raw_ref, base_dir=ref_path.parent)
            require(raw_path.suffix != ".json", "Raw instrumentation evidence must be text, not another claim")
            verify_source_evidence(str(raw_path), base_dir, mode, declared_status, test_class, test_method)
            emitted = []
            for line in raw_path.read_text().splitlines():
                if "HelixAcceptance:" in line:
                    candidate = json.loads(line.split("HelixAcceptance:", 1)[1].strip(),
                                           object_pairs_hook=unique_object_hook)
                    if candidate.get("test_class") == test_class and candidate.get("test_method") == test_method:
                        emitted.append(candidate.get("metrics"))
            for key, value in (metrics or {}).items():
                require(ev_data.get("metrics", {}).get(key) == value,
                        f"Evidence lacks matching measured metric {key}")
                require(len(emitted) == 1 and isinstance(emitted[0], dict) and emitted[0].get(key) == value,
                        f"Raw test log lacks matching measured metric {key}")
        else:
            identity = re.escape(f"{test_method}({test_class})")
            started = re.findall(r"TestRunner:\s*started:\s*" + identity + r"\s*$", raw_text, re.M)
            finished = re.findall(r"TestRunner:\s*finished:\s*" + identity + r"\s*$", raw_text, re.M)
            unsuccessful = re.search(r"TestRunner:\s*(?:failed|assumption failed|ignored):\s*" + identity,
                                     raw_text)
            require(
                len(started) == 1 and len(finished) == 1 and not unsuccessful,
                f"Evidence {source_ref} lacks one successful TestRunner record for {test_class}::{test_method}",
                exit_code=2,
            )
            require(not metrics, "Measured metrics require structured evidence linked to the raw test log")


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
        require(self.mode in ("real", "fixture"), f"Mode must be 'real' or 'fixture', got: {self.mode}")
        if self.mode == "real":
            require(
                bool(self.commit_sha and len(self.commit_sha.strip()) == 40 and all(c in "0123456789abcdefABCDEF" for c in self.commit_sha.strip())),
                "Real mode requires full 40-character hex commit_sha",
            )
            require(
                bool(self.app_apk_sha256 and len(self.app_apk_sha256) == 64 and all(c in "0123456789abcdefABCDEF" for c in self.app_apk_sha256)),
                "Real mode requires full 64-character hex app_apk_sha256",
            )
            require(
                bool(self.test_apk_sha256 and len(self.test_apk_sha256) == 64 and all(c in "0123456789abcdefABCDEF" for c in self.test_apk_sha256)),
                "Real mode requires full 64-character hex test_apk_sha256",
            )
        else:
            require(
                bool(self.commit_sha and 7 <= len(self.commit_sha.strip()) <= 40 and all(c in "0123456789abcdefABCDEF" for c in self.commit_sha.strip())),
                "Invalid or non-hex commit_sha",
            )
        if self.api is not None:
            require(isinstance(self.api, int) and 21 <= self.api <= 36, f"Invalid Android API level: {self.api}")
        if self.flavor is not None:
            require(isinstance(self.flavor, str) and self.flavor in ("developer", "consumer"), f"Invalid flavor: {self.flavor}")


@dataclass
class DeviceLifecycle:
    owner_pid: Optional[int] = None
    closed: bool = False
    details: Dict[str, Any] = field(default_factory=dict)

    def validate(self, mode: str):
        if mode == "real":
            require(type(self.owner_pid) is int and self.owner_pid > 0, "Real mode requires valid owner_pid")
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
        require(type(self.expected) is int and self.expected >= 0, "expected count must be non-negative integer")
        require(type(self.executed) is int and self.executed >= 0, "executed count must be non-negative integer")
        require(type(self.passed) is int and self.passed >= 0, "passed count must be non-negative integer")
        require(type(self.failed) is int and self.failed >= 0, "failed count must be non-negative integer")
        require(type(self.skipped) is int and self.skipped >= 0, "skipped count must be non-negative integer")
        require(self.executed > 0, "Zero test executions detected; cannot validate acceptance")
        require(
            self.executed == self.passed + self.failed + self.skipped,
            f"Count mismatch: executed ({self.executed}) != passed ({self.passed}) + failed ({self.failed}) + skipped ({self.skipped})",
        )
        require(
            self.expected == self.executed,
            f"Count mismatch: expected ({self.expected}) != executed ({self.executed})",
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
