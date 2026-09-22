"""Stage byte-identical production sources and review probes into ignored build/.

Run from repository root. The init script adds only test inputs. All outputs are
local; no device, service, account, dependency or production code is modified.
"""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess

root = Path.cwd()
base = root / "scripts/debug/2026-09-22/deep-review"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", type=Path, default=Path("build/deep-review"))
args = parser.parse_args()
out = args.output.resolve()
if not out.is_relative_to((root / "build").resolve()):
    raise SystemExit("Output must be inside ignored build/")
out.mkdir(parents=True, exist_ok=True)
files = subprocess.check_output(["git", "ls-files", "-z"], text=True).split("\0")
production = [p for p in files if p and ("/src/" in p or p.endswith((".gradle.kts", ".toml"))) and (root / p).is_file()]
snapshot = {p: hashlib.sha256((root / p).read_bytes()).hexdigest() for p in production}
baseline = out / "production-before.json"
if baseline.exists():
    raise SystemExit("Use a fresh output directory; do not mix review runs")
baseline.write_text(json.dumps(snapshot, indent=2) + "\n")
(out / "head.txt").write_bytes(subprocess.check_output(["git", "rev-parse", "HEAD"]))
copies = {
    "app/src/main/kotlin/com/helix/app/chat/SessionTurnAdmission.kt": "agent/SessionTurnAdmission.kt",
    str(base.relative_to(root) / "AdmissionReviewProbe.kt"): "agent/AdmissionReviewProbe.kt",
    str(base.relative_to(root) / "SchedulerReviewProbe.kt"): "framework/SchedulerReviewProbe.kt",
    str(base.relative_to(root) / "StorageReviewProbe.kt"): "storage/StorageReviewProbe.kt",
    str(base.relative_to(root) / "ProviderReviewProbe.kt"): "provider/ProviderReviewProbe.kt",
}
for source, target in copies.items():
    if not (root / source).is_file():
        raise SystemExit(f"Missing probe input: {source}")
    destination = out / "sources" / target
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(root / source, destination)
(out / "probe-inputs.json").write_text(json.dumps({source: hashlib.sha256((root / source).read_bytes()).hexdigest()
                                                for source in copies}, indent=2) + "\n")
print(f"Preserved production baseline; staged review-only test sources ({len(snapshot)} current files).")
