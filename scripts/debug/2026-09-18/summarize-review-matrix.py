#!/usr/bin/env python3
"""Require complete merged-source evidence, including owner-death follow-up and shutdown."""
import ast
import json
from pathlib import Path
import re

root = Path(__file__).resolve().parents[3]
rows = []
for variant, api, directory in [
    ("consumer", 29, "review-merged-consumer29-r2"),
    ("consumer", 36, "review-merged-consumer36"),
    ("developer", 29, "review-merged-developer29"),
    ("developer", 36, "review-merged-developer36"),
]:
    evidence = root / "build" / directory
    output = (evidence / "instrumentation.txt").read_text()
    assert "FAILURES" not in output and "INSTRUMENTATION_FAILED" not in output, directory
    count = int(re.search(r"OK \((\d+) tests?\)", output).group(1))
    assert count == (16 if variant == "consumer" else 59), directory
    assert json.loads((evidence / "closed.json").read_text())["exit"] == 0, directory
    storage = 0
    if variant == "consumer":
        values = ast.literal_eval((evidence / "follow-up.txt").read_text().strip())
        assert values == {
            "com.helix.core.storage.RoomMigrationFixtureTest": 35,
            "com.helix.core.storage.ProviderConfigAndSecretStoreTest": 12,
        }, directory
        storage = sum(values.values())
    else:
        owner = json.loads((evidence / "owner-death.json").read_text())
        assert owner["mainPid"] != owner["runtimePid"], directory
        assert owner["record"]["state"] == "SUCCEEDED", directory
        assert owner["record"]["outputManifestSha256"] and owner["record"]["terminalCommit"], directory
    rows.append(dict(variant=variant, api=api, appTests=count, storageTests=storage,
                     artifacts=json.loads((evidence / "artifacts.json").read_text())))
for variant in ("consumer", "developer"):
    pair = [row["artifacts"] for row in rows if row["variant"] == variant]
    assert pair[0] == pair[1], variant
summary = dict(rows=rows, totalInstrumentationTests=sum(row["appTests"] + row["storageTests"] for row in rows),
               ownerProbePreparationTests=2, independentOwnerDeathChecks=2)
target = root / "build/review-merged-matrix-summary.json"
target.write_text(json.dumps(summary, indent=2) + "\n")
print(target.read_text())
