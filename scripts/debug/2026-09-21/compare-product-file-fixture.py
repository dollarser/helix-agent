#!/usr/bin/env python3
"""Compare actual identical file-journey fixtures, never infer improvement from test counts."""
import argparse
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(root / "scripts"))
from owned_acceptance import collect_owned, sha256

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--baseline", type=Path, required=True)
parser.add_argument("--current-summary", type=Path, required=True)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
current = json.loads(args.current_summary.read_text())
rows = []
for batch in current["batches"]:
    if not batch["batch"].endswith("-file-task"):
        continue
    label = batch["batch"]
    plan = json.loads((args.baseline / f"{label}-expected.json").read_text())
    for path, digest in plan["source_sha256"].items():
        assert sha256(root / path) == digest, "The compared fixture source changed"
    before = collect_owned(args.baseline / label, plan["methods"])
    after = collect_owned(root / batch["source"], plan["methods"])
    assert before["verdict"] == after["verdict"] == "DEVICE_BATCH_PASS"
    assert before["artifacts"]["test"] == after["artifacts"]["test"], "Different test APKs"
    assert before["artifacts"]["app"] != after["artifacts"]["app"], "Identical production APKs"
    before_rows = {row["test_method"]: row["metrics"] for row in before["tests"]}
    after_rows = {row["test_method"]: row["metrics"] for row in after["tests"]}
    assert before_rows.keys() == after_rows.keys() and len(before_rows) == 4
    rows.append({"batch": label, "before": before_rows, "after": after_rows,
                 "metrics_unchanged": before_rows == after_rows,
                 "before_artifacts": before["artifacts"], "after_artifacts": after["artifacts"]})
assert len(rows) == 4, "Require both APIs and flavors"
args.output.parent.mkdir(parents=True, exist_ok=True)
with args.output.open("x") as stream:
    json.dump({"fixed_journeys_before": 16, "fixed_journeys_after": 16, "rows": rows}, stream, indent=2)
    stream.write("\n")
print(json.dumps({"compared": 16, "metrics_unchanged": all(row["metrics_unchanged"] for row in rows)}))
