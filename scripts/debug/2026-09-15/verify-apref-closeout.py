"""Owned API29/36 device regression for the HXA-200 contract corrections.

Requires JAVA_HOME and ANDROID_HOME. Never uses an existing device; preserves the
AVD with read-only mode and terminates only the child emulator in finally.
"""
import datetime
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[3]
sdk = Path(os.environ["ANDROID_HOME"])
adb = str(sdk / "platform-tools/adb")
emulator = str(sdk / "emulator/emulator")
out = root / "build" / ("hxa200-closeout-" + datetime.datetime.now().strftime("%Y%m%d-%H%M%S"))
out.mkdir(parents=True)
print(out.relative_to(root), flush=True)


def run(args, **kwargs):
    return subprocess.run(args, cwd=root, check=True, **kwargs)


def devices():
    return run([adb, "devices"], capture_output=True, text=True).stdout.strip().splitlines()[1:]


results = []
classes = "com.helix.app.ToolApprovalPreferenceDeviceTest,com.helix.app.ToolSchedulerDeviceTest,com.helix.app.ToolPreferenceStopDeviceTest"
storage_only = "--storage" in sys.argv[1:]
if storage_only:
    classes = "com.helix.core.storage.RoomMigrationFixtureTest,com.helix.core.storage.ApprovalProofLifecycleTest"
expected_tests = 38 if storage_only else 25
if "--stop-only" in sys.argv[1:]:
    classes = "com.helix.app.ToolPreferenceStopDeviceTest"
    expected_tests = 2
try:
    for api, port in ([(29, 5574)] if "--api29" in sys.argv[1:] else [(29, 5574), (36, 5576)]):
        if devices():
            raise RuntimeError("Refusing existing adb devices, including offline devices")
        serial = f"emulator-{port}"
        with (out / f"emulator-{api}.log").open("w") as log:
            child = subprocess.Popen([emulator, "-avd", f"Helix_API_{api}", "-port", str(port),
                                      "-read-only", "-no-window", "-no-audio", "-no-snapshot",
                                      "-memory", "2048", "-cores", "2", "-gpu", "swiftshader_indirect"],
                                     stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 300
                while time.monotonic() < deadline:
                    if child.poll() is not None:
                        raise RuntimeError("Owned emulator exited during boot")
                    probe = subprocess.run([adb, "-s", serial, "shell", "getprop", "sys.boot_completed"],
                                           capture_output=True, text=True)
                    if probe.stdout.strip() == "1":
                        break
                    time.sleep(2)
                else:
                    raise RuntimeError("Owned emulator boot timed out")
                for command in [("size", "1080x2400"), ("density", "420")]:
                    run([adb, "-s", serial, "shell", "wm", *command], capture_output=True)
                env = dict(os.environ, ANDROID_SERIAL=serial)
                for flavor in (["Storage"] if storage_only else ["Consumer", "Developer"]):
                    # Only the new owned emulator's two known app packages are reset.
                    package = "com.helix.agent" + (".developer" if flavor == "Developer" else "")
                    if storage_only:
                        package = "com.helix.core.storage"
                    for name in [package + ".test", package]:
                        subprocess.run([adb, "-s", serial, "uninstall", name], capture_output=True)
                    task = f":app:connected{flavor}DebugAndroidTest"
                    if storage_only:
                        task = ":core:storage:connectedDebugAndroidTest"
                    if "--process-restart" in sys.argv[1:]:
                        with (out / f"{api}-{flavor}-assemble.log").open("w") as buildlog:
                            run(["./gradlew", f":app:assemble{flavor}Debug", f":app:assemble{flavor}DebugAndroidTest",
                                 "--console=plain"], env=env, stdout=buildlog, stderr=subprocess.STDOUT)
                        apk_root = root / "app/build/outputs/apk"
                        apk = apk_root / f"{flavor.lower()}/debug/app-{flavor.lower()}-debug.apk"
                        test_apk = apk_root / f"androidTest/{flavor.lower()}/debug/app-{flavor.lower()}-debug-androidTest.apk"
                        for package_file in [apk, test_apk]:
                            run([adb, "-s", serial, "install", "-r", str(package_file)], capture_output=True)
                        # Direct instrumentation retains app data. Gradle's device installer resets it between runs.
                        for phase, selected, expected in [
                            ("stop", "com.helix.app.ToolPreferenceStopDeviceTest", 2),
                            ("restart", "com.helix.app.ToolPreferenceRestartDeviceTest", 1),
                        ]:
                            run([adb, "-s", serial, "shell", "am", "force-stop", package], capture_output=True)
                            command = [adb, "-s", serial, "shell", "am", "instrument", "-w", "-r", "-e", "hxa200RecoveryPhase", phase, "-e", "class",
                                       selected, package + ".test/com.helix.app.HelixAndroidJUnitRunner"]
                            result = subprocess.run(command, cwd=root, capture_output=True, text=True, timeout=180)
                            raw = result.stdout + result.stderr
                            (out / f"{api}-{flavor}-{phase}.txt").write_text(raw)
                            codes = [int(x) for x in re.findall(r"INSTRUMENTATION_STATUS_CODE: (-?\d+)", raw)]
                            passed = codes.count(0)
                            failed = sum(x not in (0, 1) for x in codes)
                            row = dict(api=api, flavor=flavor, phase=phase, tests=passed, failures=failed, exit=result.returncode)
                            results.append(row)
                            print(json.dumps(row), flush=True)
                            if result.returncode or passed != expected or failed or "INSTRUMENTATION_CODE: -1" not in raw:
                                raise RuntimeError("Process restart instrumentation did not pass its exact test count")
                        continue
                    phases = [("dispatch", classes, expected_tests)]
                    for phase, selected, expected in phases:
                        if phase == "restart":
                            run([adb, "-s", serial, "shell", "am", "force-stop", package], capture_output=True)
                        source = root / f"app/build/outputs/androidTest-results/connected/debug/flavors/{flavor.lower()}"
                        if storage_only:
                            source = root / "core/storage/build/outputs/androidTest-results/connected/debug"
                        if source.exists():
                            shutil.rmtree(source)
                        with (out / f"{api}-{flavor}-{phase}.log").open("w") as testlog:
                            result = subprocess.run(["./gradlew", task,
                                                     "-Pandroid.testInstrumentationRunnerArguments.class=" + selected,
                                                     "--console=plain"], cwd=root, env=env,
                                                    stdout=testlog, stderr=subprocess.STDOUT)
                        dest = out / f"xml-{api}-{flavor}-{phase}"
                        if not source.exists():
                            raise RuntimeError(f"No fresh device XML: {api}/{flavor}/{phase}; Gradle exit {result.returncode}")
                        shutil.copytree(source, dest)
                        totals = dict(tests=0, failures=0, errors=0, skipped=0)
                        for xml in dest.rglob("TEST-*.xml"):
                            suite = ET.parse(xml).getroot()
                            for key in totals:
                                totals[key] += int(suite.get(key, "0"))
                        row = dict(api=api, flavor=flavor, phase=phase, exit=result.returncode, **totals)
                        results.append(row)
                        print(json.dumps(row), flush=True)
                        if result.returncode or totals["tests"] != expected or any(totals[k] for k in ["failures", "errors", "skipped"]):
                            raise RuntimeError(f"Device regression failed or expected {expected} tests not executed")
            finally:
                child.terminate()
                try:
                    child.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=10)
                (out / f"owned-exit-{api}.txt").write_text(str(child.returncode))
                for _ in range(30):
                    if not devices():
                        break
                    time.sleep(1)
finally:
    (out / "summary.json").write_text(json.dumps(results, indent=2))
    (out / "adb-final.txt").write_text(run([adb, "devices"], capture_output=True, text=True).stdout)
