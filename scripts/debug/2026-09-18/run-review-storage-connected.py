#!/usr/bin/env python3
"""Run storage migration and Secret tests on the caller's owned emulator only."""
import os
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

serial, output = sys.argv[1:]
root = Path(__file__).resolve().parents[3]
classes = [
    "com.helix.core.storage.RoomMigrationFixtureTest",
    "com.helix.core.storage.ProviderConfigAndSecretStoreTest",
]
started = time.time()
with (Path(output) / "storage-connected.log").open("w") as log:
    subprocess.run([
        str(root / "gradlew"), ":core:storage:connectedDebugAndroidTest",
        "-Pandroid.testInstrumentationRunnerArguments.class=" + ",".join(classes),
    ], cwd=root, env=dict(os.environ, ANDROID_SERIAL=serial), stdout=log,
        stderr=subprocess.STDOUT, check=True)
counts = {name: 0 for name in classes}
for path in (root / "core/storage/build/outputs/androidTest-results/connected").glob("**/TEST-*.xml"):
    if path.stat().st_mtime < started:
        continue
    for suite in ET.parse(path).getroot().iter("testsuite"):
        name = suite.get("name")
        if name in counts:
            assert int(suite.get("failures", "0")) == 0, name
            assert int(suite.get("errors", "0")) == 0, name
            assert int(suite.get("skipped", "0")) == 0, name
            counts[name] += int(suite.get("tests", "0"))
assert all(counts.values()), counts
print(counts)
