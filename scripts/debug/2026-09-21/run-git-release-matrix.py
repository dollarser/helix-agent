#!/usr/bin/env python3
"""Sign release APKs with the local debug certificate for replacement tests only; no publication."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[3]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--api", type=int, choices=(29, 36), action="append")
parser.add_argument("--flavor", choices=("consumer", "developer"), action="append")
parser.add_argument("--first-port", type=int, default=5730)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=False)
sdk = Path(os.environ["ANDROID_HOME"])
signers = sorted((sdk / "build-tools").glob("*/apksigner"))
if not signers:
    raise RuntimeError("Android build-tools apksigner missing")
port = args.first_port
for flavor in args.flavor or ("consumer", "developer"):
    unsigned = root / f"app/build/outputs/apk/{flavor}/release/app-{flavor}-release-unsigned.apk"
    signed = args.output / f"{flavor}-release-test-signed.apk"
    subprocess.run([str(signers[-1]), "sign", "--ks", str(Path.home() / ".android/debug.keystore"),
                    "--ks-key-alias", "androiddebugkey", "--ks-pass", "pass:android", "--key-pass", "pass:android",
                    "--out", str(signed), str(unsigned)], check=True)
    subprocess.run([str(signers[-1]), "verify", str(signed)], check=True)
    (args.output / f"{flavor}-signing.json").write_text(json.dumps({
        "unsignedSha256": hashlib.sha256(unsigned.read_bytes()).hexdigest(),
        "signedSha256": hashlib.sha256(signed.read_bytes()).hexdigest(),
        "purpose": "local release behavior test; debug certificate, not a release publication",
    }, indent=2) + "\n")
    package = "com.helix.agent" + (".developer" if flavor == "developer" else "")
    for api in args.api or (29, 36):
        target = args.output / f"{flavor}-api{api}"
        command = [sys.executable, "scripts/debug/2026-09-18/run-owned-emulator-207.py",
                   "--avd", f"Helix191_API{api}", "--port", str(port), "--memory-mb", "4096", "--cores", "4",
                   "--apk", f"app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk",
                   "--test-apk", f"app/build/outputs/apk/androidTest/{flavor}/debug/app-{flavor}-debug-androidTest.apk",
                   "--runner", f"{package}.test/com.helix.app.HelixAndroidJUnitRunner",
                   "--classes", "com.helix.app.ui.GitReleaseFixtureDeviceTest", "--instrument-arg", "gitBoundaryPhase=prepare",
                   "--after-script", "scripts/debug/2026-09-21/git-release-follow-up.py", "--output", str(target), "--timeout", "300"]
        environment = dict(os.environ, HELIX_GIT_RELEASE_APK=str(signed.resolve()), HELIX_GIT_PACKAGE=package)
        with (args.output / f"{flavor}-api{api}.log").open("w") as log:
            subprocess.run(command, cwd=root, env=environment, stdout=log, stderr=subprocess.STDOUT, check=True)
        port += 2
