"""Owned API29/36 device regression for the HXA-200 contract corrections.

Requires JAVA_HOME and ANDROID_HOME. Never uses an existing device; preserves the
AVD with read-only mode and terminates only the child emulator in finally.
"""
import datetime
import json
import os
from pathlib import Path
import shutil
import subprocess
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
classes = "com.helix.app.ToolApprovalPreferenceDeviceTest,com.helix.app.ToolSchedulerDeviceTest"
try:
    for api, port in [(29, 5574), (36, 5576)]:
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
                for flavor in ["Consumer", "Developer"]:
                    # Only the new owned emulator's two known app packages are reset.
                    package = "com.helix.agent" + (".developer" if flavor == "Developer" else "")
                    for name in [package + ".test", package]:
                        subprocess.run([adb, "-s", serial, "uninstall", name], capture_output=True)
                    task = f":app:connected{flavor}DebugAndroidTest"
                    with (out / f"{api}-{flavor}.log").open("w") as testlog:
                        result = subprocess.run(["./gradlew", task,
                                                 "-Pandroid.testInstrumentationRunnerArguments.class=" + classes,
                                                 "--console=plain"], cwd=root, env=env,
                                                stdout=testlog, stderr=subprocess.STDOUT)
                    source = root / f"app/build/outputs/androidTest-results/connected/debug/flavors/{flavor.lower()}"
                    dest = out / f"xml-{api}-{flavor}"
                    shutil.copytree(source, dest)
                    totals = dict(tests=0, failures=0, errors=0, skipped=0)
                    for xml in dest.rglob("TEST-*.xml"):
                        suite = ET.parse(xml).getroot()
                        for key in totals:
                            totals[key] += int(suite.get(key, "0"))
                    row = dict(api=api, flavor=flavor, exit=result.returncode, **totals)
                    results.append(row)
                    print(json.dumps(row), flush=True)
                    if result.returncode or totals["tests"] != 22 or any(totals[k] for k in ["failures", "errors", "skipped"]):
                        raise RuntimeError("Device regression failed or expected 9+13 tests not executed")
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
