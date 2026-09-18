#!/usr/bin/env python3
"""Stage the reviewed repair paths, preserving the owner's original review file."""
from pathlib import Path
import hashlib
import subprocess

root = Path(__file__).resolve().parents[3]
review = root / "REVIEW-2026-09-18.md"
before = hashlib.sha256(review.read_bytes()).hexdigest()
tracked = subprocess.check_output(["git", "diff", "--name-only", "-z"], cwd=root).decode().split("\0")
new = [
    "app/src/androidTest/assets/com.helix.core.storage.HelixDatabase/22.json",
    "app/src/androidTest/kotlin/com/helix/app/PermissionAtomicityDeviceTest.kt",
    "core/storage/src/androidTest/assets/com.helix.core.storage.HelixDatabase/23.json",
    "docs/evidence/development/review-followup-2026-09-18.md",
    "docs/development/claude-handoff-207-191-206.md",
    "runtime/cli-app/src/main/kotlin/com/helix/runtime/cli/app/SpoolingModelEvents.kt",
    "runtime/cli-app/src/test/kotlin/com/helix/runtime/cli/app/CodexJobProgressTest.kt",
    "runtime/cli-app/src/test/kotlin/com/helix/runtime/cli/app/SpoolingModelEventsTest.kt",
    "runtime/cli-client/src/main/kotlin/com/helix/runtime/cli/client/CliEventPrefix.kt",
    "runtime/cli-client/src/test/kotlin/com/helix/runtime/cli/client/CliEventPrefixTest.kt",
]
scripts = [
    "apply-permission-atomic-fix.py", "apply-stability-ui-fix.py",
    "run-review-repair-device.sh", "run-review-repair-matrix.sh",
    "run-review-storage-connected.py", "commit-review-repairs.py",
]
paths = [path for path in tracked if path] + new + ["scripts/debug/2026-09-18/" + name for name in scripts]
assert "REVIEW-2026-09-18.md" not in paths
subprocess.run(["git", "add", "--", *paths], cwd=root, check=True)
assert hashlib.sha256(review.read_bytes()).hexdigest() == before
