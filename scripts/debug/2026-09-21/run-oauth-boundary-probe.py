#!/usr/bin/env python3
"""Run the saved boundary probe against compiled candidate classes, with synthetic owned files only."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tomllib

ROOT = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", type=Path, required=True)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=False)
versions = tomllib.loads((ROOT / "gradle/libs.versions.toml").read_text())["versions"]
cache = Path.home() / ".gradle/caches/modules-2/files-2.1"
app_classes = ROOT / "app/build/intermediates/built_in_kotlinc/consumerDebug/compileConsumerDebugKotlin/classes"
store_class = next((ROOT / "core/storage/build").rglob("SecretStore.class"))
store_classes = next(parent for parent in store_class.parents if parent.name == "classes")
parts = [app_classes, store_classes]
for group, artifact, version in (
        ("org.jetbrains.kotlin", "kotlin-stdlib", versions["kotlin"]),
        ("org.jetbrains.kotlinx", "kotlinx-serialization-json-jvm", versions["serialization"]),
        ("org.jetbrains.kotlinx", "kotlinx-serialization-core-jvm", versions["serialization"])):
    parts.append(next((cache / group / artifact / version).glob("*/*.jar")))
source = ROOT / "app/src/main/kotlin/com/helix/app/mcp/oauth/McpOAuthAttemptStore.kt"
provenance = {"head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "source_sha256": hashlib.sha256(source.read_bytes()).hexdigest(),
              "class_sha256": hashlib.sha256((app_classes / "com/helix/app/mcp/oauth/McpOAuthAttemptStore.class").read_bytes()).hexdigest()}
(args.output / "provenance.json").write_text(json.dumps(provenance, indent=2))
command = [str(Path(os.environ["JAVA_HOME"]) / "bin/java"), "--class-path", os.pathsep.join(map(str, parts)),
           str(Path(__file__).with_name("OAuthAttemptBoundaryProbe.java")), str(args.output), provenance["source_sha256"]]
with (args.output / "probe.log").open("w") as log:
    result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
print((args.output / "probe.log").read_text())
raise SystemExit(result.returncode)
