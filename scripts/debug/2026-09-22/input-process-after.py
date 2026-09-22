#!/usr/bin/env python3
"""Drive one real MainActivity input boundary through SIGKILL and verify recovery."""

import hashlib
import json
import os
from pathlib import Path
import queue
import re
import subprocess
import sys
import threading
import time
import urllib.request
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts"))
from android_process_control import kill_emulator_app

SCENARIOS = {"pending", "appended", "http_in_flight", "cancelling"}
VERIFY_METHODS = {
    "pending": "verifyPendingProcessRecovery",
    "appended": "verifyAppendedProcessRecovery",
    "http_in_flight": "verifyHttpInFlightProcessRecovery",
    "cancelling": "verifyCancellingProcessRecovery",
}
BREAKPOINTS = {
    "appended": {
        "source": ROOT / "app/src/main/kotlin/com/helix/app/chat/ChatService.kt",
        # TurnCoordinator.start is nested inside launchTurn's transaction. Stop only after
        # the OUTERMOST transaction returned, before the worker can start a model request.
        "marker": "val effectiveGoalId = preparedGoalId",
        "class": "com.helix.app.chat.ChatService",
    },
    "cancelling": {
        "source": ROOT / "app/src/main/kotlin/com/helix/app/chat/ChatService.kt",
        "marker": "publishTurn(TurnUi(turnId, TurnState.CANCELLING",
        "class": "com.helix.app.chat.ChatService$cancelTurn$2",
    },
}

serial, destination = sys.argv[1:]
output = Path(destination)
owner = json.loads((output / "owner.json").read_text())
if owner.get("serial") != serial:
    raise RuntimeError("After-script serial does not match the owned emulator")
os.kill(owner["pid"], 0)
package = os.environ["HXA216_PACKAGE"]
scenario = os.environ["HXA216_SCENARIO"]
server_port = int(os.environ["HXA216_SERVER_PORT"])
flavor = os.environ["HXA216_FLAVOR"]
if package not in ("com.helix.agent", "com.helix.agent.developer"):
    raise RuntimeError("Refusing to operate on a non-Helix package")
if scenario not in SCENARIOS:
    raise RuntimeError("Unknown process-recovery scenario: " + scenario)
if flavor not in ("consumer", "developer"):
    raise RuntimeError("Unknown debug flavor: " + flavor)
adb_path = str(Path(os.environ["ANDROID_HOME"]) / "platform-tools/adb")
base = [adb_path, "-s", serial]
runner = package + ".test/com.helix.app.HelixAndroidJUnitRunner"
test_class = "com.helix.app.chat.SessionInputProcessRecoveryDeviceTest"


def adb(*args, timeout=40):
    return subprocess.check_output(base + list(args), text=True, timeout=timeout)


def control(path, value=None):
    url = f"http://127.0.0.1:{server_port}/__control/{path}"
    body = None if value is None else json.dumps(value).encode()
    request = urllib.request.Request(url, data=body, method="GET" if body is None else "POST")
    request.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.loads(response.read())


def wait_server(predicate, label, timeout=20):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = control("state")
        if predicate(last):
            (output / f"server-{label}.json").write_text(json.dumps(last, indent=2))
            return last
        time.sleep(0.2)
    raise RuntimeError(f"Server boundary {label} not reached: {last}")


def nodes(label):
    for attempt in range(8):
        remote = "/sdcard/helix-input-process.xml"
        adb("shell", "rm", "-f", remote)
        result = adb("shell", "uiautomator", "dump", remote)
        if "UI hierchary dumped to:" not in result:
            (output / f"{label}-dump-{attempt}.txt").write_text(result)
            time.sleep(0.4)
            continue
        raw = adb("shell", "cat", remote)
        (output / f"{label}.xml").write_text(raw)
        return list(ET.fromstring(raw).iter("node"))
    raise RuntimeError("No fresh accessibility snapshot for " + label)


def wait_node(predicate, label, timeout=20):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        found = next((node for node in nodes(label) if predicate(node)), None)
        if found is not None:
            return found
        time.sleep(0.4)
    raise RuntimeError("UI boundary not found: " + label)


def text_or_description(node):
    return (node.get("text") or "") + "\n" + (node.get("content-desc") or "")


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def open_session(title):
    adb("shell", "am", "start", "-W", "-n", package + "/com.helix.app.MainActivity")
    title_node = wait_node(lambda node: node.get("text") == title, "session-list")
    tap(title_node)
    wait_node(lambda node: node.get("class") == "android.widget.EditText", "session-open")


def enter_and_send(text):
    field = wait_node(lambda node: node.get("class") == "android.widget.EditText", "composer")
    tap(field)
    adb("shell", "input", "text", text)
    wait_node(lambda node: node.get("class") == "android.widget.EditText" and node.get("text") == text, "typed")
    send = wait_node(lambda node: (node.get("content-desc") or "") in ("发送", "Send"), "send")
    if send.get("enabled") != "true":
        raise RuntimeError("Send action was not enabled")
    tap(send)


def wait_pending_panel():
    return wait_node(
        lambda node: (
            ("Input delivery" in text_or_description(node) and "pending 1" in text_or_description(node))
            or ("输入交付" in text_or_description(node) and "待处理 1" in text_or_description(node))
        ),
        "pending-panel",
    )


def source_line(spec):
    matches = [index for index, line in enumerate(spec["source"].read_text().splitlines(), 1) if spec["marker"] in line]
    if len(matches) != 1:
        raise RuntimeError(f"Expected one source marker {spec['marker']!r}, found {matches}")
    return matches[0]


def validate_line_table(spec, line):
    variant = flavor.capitalize() + "Debug"
    classes = ROOT / f"app/build/intermediates/built_in_kotlinc/{flavor}Debug/compile{variant}Kotlin/classes"
    if not classes.is_dir():
        raise RuntimeError("Compiled debug classes missing: " + str(classes))
    result = subprocess.check_output(
        ["javap", "-classpath", str(classes), "-l", "-p", spec["class"]], text=True, timeout=30
    )
    if re.search(rf"\bline {line}:\s*\d+", result) is None:
        raise RuntimeError(f"Source marker line {line} is absent from {spec['class']} LineNumberTable")
    (output / f"javap-{scenario}.txt").write_text(result)


def jdwp_snapshot():
    # Recent adb keeps jdwp open to publish process changes. A bounded snapshot is enough;
    # check_output kills and reaps its child on timeout before exposing the captured bytes.
    try:
        listing = adb("jdwp", timeout=3)
    except subprocess.TimeoutExpired as error:
        listing = error.output or b""
    if isinstance(listing, bytes):
        listing = listing.decode("utf-8", errors="strict")
    return set(listing.split())


class JdbBoundary:
    def __init__(self, pid, spec):
        self.pid = pid
        self.spec = spec
        self.line = source_line(spec)
        self.fragments = []
        self.updates = queue.Queue()
        self.port = None
        self.process = None
        self.reader = None
        try:
            validate_line_table(spec, self.line)
            jdwp = jdwp_snapshot()
            if str(pid) not in jdwp:
                raise RuntimeError(f"Normal debug process {pid} is not exposed through adb jdwp")
            self.port = int(adb("forward", "tcp:0", f"jdwp:{pid}").strip())
            self.process = subprocess.Popen(
                ["jdb", "-attach", f"localhost:{self.port}"],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                bufsize=0,
            )
            self.reader = threading.Thread(target=self._read, args=(self.process,), daemon=True)
            self.reader.start()
            self._wait_all(("Initializing jdb", ">"), 15)
            self._send(f"stop at {spec['class']}:{self.line}")
            self._wait_any(("Set breakpoint", "Deferring breakpoint"), 15)
        except BaseException as error:
            for cleanup_error in self._shutdown():
                error.add_note("JDB cleanup failed: " + cleanup_error)
            raise

    def _read(self, process):
        if process.stdout is None:
            raise RuntimeError("jdb stdout pipe was not created")
        while True:
            char = process.stdout.read(1)
            if char == "":
                return
            self.fragments.append(char)
            self.updates.put(None)

    def _wait_all(self, needles, timeout):
        return self._wait(needles, timeout, require_all=True)

    def _wait_any(self, needles, timeout):
        return self._wait(needles, timeout, require_all=False)

    def _wait(self, needles, timeout, require_all):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            transcript = "".join(self.fragments)
            if "Unable to set" in transcript or "Exception" in transcript:
                raise RuntimeError("jdb rejected breakpoint:\n" + transcript)
            # jdb formats large source lines with locale grouping (for example line=3,448).
            searchable = re.sub(r"line=([0-9,]+)", lambda match: match[0].replace(",", ""), transcript)
            matches = (needle in searchable for needle in needles)
            if (all(matches) if require_all else any(matches)):
                return transcript
            if self.process is not None and self.process.poll() is not None:
                raise RuntimeError("jdb exited before reaching boundary:\n" + transcript)
            try:
                self.updates.get(timeout=0.2)
            except queue.Empty:
                pass
        mode = "all" if require_all else "any"
        raise RuntimeError(f"jdb timeout waiting for {mode} of {needles}:\n{''.join(self.fragments)}")

    def _send(self, command):
        if self.process is None or self.process.stdin is None:
            raise RuntimeError("jdb stdin pipe is unavailable")
        self.process.stdin.write(command + "\n")
        self.process.stdin.flush()

    def wait_hit(self):
        transcript = self._wait_all(("Breakpoint hit", f"line={self.line}"), 30)
        if self.spec["class"].split("$")[0] not in transcript:
            raise RuntimeError("jdb hit did not identify the expected production class")
        return transcript

    def _shutdown(self):
        errors = []
        process = self.process
        if process is not None:
            try:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
            except Exception as error:  # Cleanup still has to remove the adb forward.
                errors.append(f"jdb process: {error}")
            finally:
                self.process = None
        if self.reader is not None and self.reader.is_alive():
            self.reader.join(timeout=1)
            if self.reader.is_alive():
                errors.append("jdb output reader did not stop")
        if self.port is not None:
            try:
                adb("forward", "--remove", f"tcp:{self.port}")
            except Exception as error:
                errors.append(f"adb forward tcp:{self.port}: {error}")
            finally:
                self.port = None
        return errors

    def close(self):
        errors = self._shutdown()
        transcript = "".join(self.fragments)
        path = output / f"jdb-{scenario}.txt"
        path.write_text(transcript)
        evidence = {
            "class": self.spec["class"],
            "source": str(self.spec["source"].relative_to(ROOT)),
            "marker": self.spec["marker"],
            "line": self.line,
            "transcriptSha256": hashlib.sha256(transcript.encode()).hexdigest(),
            "hit": "Breakpoint hit" in transcript and any(
                int(value.replace(",", "")) == self.line
                for value in re.findall(r"line=([0-9,]+)", transcript)
            ),
        }
        if errors:
            raise RuntimeError("; ".join(errors))
        return evidence


fixture = json.loads(adb("shell", "run-as", package, "cat", "files/session-input-process-fixture.json"))
if fixture.get("scenario") != scenario or fixture.get("serverPort") != server_port:
    raise RuntimeError("Seed fixture does not match the host scenario/server")
(output / "fixture.json").write_text(json.dumps(fixture, indent=2))

# The seed's production connection test may use the same endpoint. Reset only after it completed,
# then hold every scenario request so the external server survives the app's SIGKILL.
control("reset", {})
control("mode", {"mode": "hold"})
adb("shell", "am", "force-stop", package)
open_session(fixture["title"])
before_pid = int(adb("shell", "pidof", package).strip())
debugger = None
breakpoint_evidence = None
try:
    if scenario == "appended":
        debugger = JdbBoundary(before_pid, BREAKPOINTS[scenario])
    enter_and_send("ACTIVE-PROCESS-INPUT")
    if scenario in ("pending", "cancelling"):
        wait_server(lambda state: state["chatCount"] == 1 and state["heldCount"] == 1, "active-held")
        enter_and_send("QUEUED-PROCESS-INPUT")
        wait_pending_panel()
    if scenario == "http_in_flight":
        wait_server(lambda state: state["chatCount"] == 1 and state["heldCount"] == 1, "http-in-flight")
    elif scenario == "appended":
        debugger.wait_hit()
    elif scenario == "cancelling":
        debugger = JdbBoundary(before_pid, BREAKPOINTS[scenario])
        stop = wait_node(lambda node: (node.get("content-desc") or "") in ("停止", "Stop"), "stop")
        if stop.get("enabled") != "true":
            raise RuntimeError("Stop action was not enabled")
        tap(stop)
        debugger.wait_hit()

    before_state = control("state")
    expected_chats = 0 if scenario == "appended" else 1
    if before_state["chatCount"] != expected_chats:
        raise RuntimeError(f"Unexpected pre-kill model request count: {before_state}")
    if expected_chats == 1:
        request = before_state["requests"][0]
        if not request["hasActiveInput"] or request["hasQueuedInput"]:
            raise RuntimeError(f"Wrong input bytes reached the model boundary: {request}")
    kill_emulator_app(base, package, before_pid)
finally:
    if debugger is not None:
        breakpoint_evidence = debugger.close()

if scenario in ("pending", "http_in_flight", "cancelling"):
    wait_server(lambda state: state["disconnectedCount"] >= 1, "killed-socket-disconnected", timeout=15)

open_session(fixture["title"])
after_pid = int(adb("shell", "pidof", package).strip())
if after_pid == before_pid:
    raise RuntimeError("MainActivity PID did not change after SIGKILL")
toggle = wait_node(
    lambda node: "Input delivery" in text_or_description(node) or "输入交付" in text_or_description(node),
    "recovered-panel",
)
tap(toggle)
if scenario in ("pending", "cancelling"):
    wait_node(
        lambda node: node.get("text")
        in ("需要处理，请检查后再继续。", "Needs attention. Review before resuming."),
        "recovered-needs-attention",
    )
elif scenario == "appended":
    wait_node(
        lambda node: node.get("text")
        in ("已加入历史，尚未纳入请求", "Added to history; not included in a request yet"),
        "recovered-appended",
    )
else:
    wait_node(
        lambda node: node.get("text")
        in ("已纳入请求（仅本地记录）", "Included in a request (local record only)"),
        "recovered-requested",
    )

expected_chats = 0 if scenario == "appended" else 1
stable = wait_server(lambda state: state["chatCount"] == expected_chats, "after-restart")
time.sleep(2)
stable_again = control("state")
if stable_again["chatCount"] != expected_chats or stable_again != stable:
    raise RuntimeError(f"Model request state changed after recovery: {stable} -> {stable_again}")

adb("logcat", "-b", "all", "-c")
verify_selector = test_class + "#" + VERIFY_METHODS[scenario]
verification = adb("shell", "am", "instrument", "-w", "-e", "class", verify_selector, runner, timeout=180)
(output / "verify-instrumentation.txt").write_text(verification)
if "OK (1 test)" not in verification or "FAILURES!!!" in verification:
    raise RuntimeError("Room verification failed:\n" + verification)
(output / "verify-logcat.txt").write_text(adb("logcat", "-d", "-s", "TestRunner"))
verified = json.loads(adb("shell", "run-as", package, "cat", "files/session-input-process-verified.json"))
if verified["scenario"] != scenario or verified["turnState"] != "INTERRUPTED":
    raise RuntimeError(f"Wrong Room verification evidence: {verified}")
(output / "room-verified.json").write_text(json.dumps(verified, indent=2))

normal = {
    "scenario": scenario,
    "beforePid": before_pid,
    "afterPid": after_pid,
    "normalActivity": True,
    "sigkill": 9,
    "breakpoint": breakpoint_evidence,
    "server": stable_again,
    "room": verified,
}
(output / "normal-process.json").write_text(json.dumps(normal, indent=2))
print(json.dumps(normal, sort_keys=True), flush=True)
