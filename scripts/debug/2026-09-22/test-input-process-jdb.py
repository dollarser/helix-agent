#!/usr/bin/env python3
"""Host-only unit checks for the HXA-216 dynamic JDB boundary helper."""

import ast
import hashlib
from pathlib import Path
import queue
import subprocess
import tempfile
import threading
import time
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[3]
TARGET = ROOT / "scripts/debug/2026-09-22/input-process-after.py"


class BlockingOutput:
    def __init__(self, initial):
        self.values = queue.Queue()
        self.feed(initial)

    def feed(self, value):
        for char in value:
            self.values.put(char)

    def read(self, _size):
        return self.values.get(timeout=2)

    def close(self):
        self.values.put("")


class FakeInput:
    def __init__(self, process, fail=False):
        self.process = process
        self.fail = fail

    def write(self, command):
        if self.fail:
            raise BrokenPipeError("synthetic write failure")
        if not command.startswith("stop at "):
            raise AssertionError(command)
        self.process.stdout.feed("Deferring breakpoint fixture.Class:2.\n> ")

    def flush(self):
        return None


class FakeProcess:
    def __init__(self, fail_write=False):
        self.stdout = BlockingOutput("Initializing jdb ...\n> ")
        self.stdin = FakeInput(self, fail_write)
        self.returncode = None

    def poll(self):
        return self.returncode

    def terminate(self):
        self.returncode = 0
        self.stdout.close()

    def kill(self):
        self.returncode = -9
        self.stdout.close()

    def wait(self, timeout=None):
        return self.returncode


def extracted_namespace(root, output):
    tree = ast.parse(TARGET.read_text())
    names = {"source_line", "validate_line_table", "jdwp_snapshot", "JdbBoundary"}
    body = [node for node in tree.body if getattr(node, "name", None) in names]
    namespace = {
        "hashlib": hashlib,
        "queue": queue,
        "re": __import__("re"),
        "threading": threading,
        "time": time,
        "ROOT": root,
        "output": output,
        "scenario": "appended",
        "flavor": "consumer",
    }
    exec(compile(ast.Module(body=body, type_ignores=[]), str(TARGET), "exec"), namespace)
    return namespace


def main():
    (ROOT / "build").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="hxa216-jdb-unit-", dir=ROOT / "build") as directory:
        root = Path(directory)
        output = root / "evidence"
        output.mkdir()
        source = root / "source.kt"
        source.write_text("before\nMARK\nafter\n")
        classes = root / "app/build/intermediates/built_in_kotlinc/consumerDebug/compileConsumerDebugKotlin/classes"
        classes.mkdir(parents=True)
        operations = []
        processes = []

        def adb(*args, timeout=40):
            if args == ("jdwp",):
                raise subprocess.TimeoutExpired(args, timeout, output=b"111\n321\n")
            if args[:2] == ("forward", "tcp:0"):
                operations.append(("add", args[2]))
                return "54321\n"
            if args[:2] == ("forward", "--remove"):
                operations.append(("remove", args[2]))
                return ""
            raise AssertionError(args)

        def popen(*_args, **_kwargs):
            process = FakeProcess()
            processes.append(process)
            return process

        fake_subprocess = SimpleNamespace(
            Popen=popen,
            check_output=lambda *_args, **_kwargs: "LineNumberTable:\n line 2: 0\n",
            PIPE=object(),
            STDOUT=object(),
            TimeoutExpired=subprocess.TimeoutExpired,
        )
        namespace = extracted_namespace(root, output)
        namespace.update(adb=adb, subprocess=fake_subprocess)
        # Functions resolve globals from their original namespace dictionary.
        namespace["jdwp_snapshot"].__globals__.update(namespace)
        boundary_class = namespace["JdbBoundary"]
        boundary_class.__init__.__globals__.update(namespace)
        source_line = namespace["source_line"]
        validate_line_table = namespace["validate_line_table"]
        spec = {"source": source, "marker": "MARK", "class": "fixture.Class"}

        if namespace["jdwp_snapshot"]() != {"111", "321"}:
            raise AssertionError("TimeoutExpired partial output did not preserve the target PID")
        if source_line(spec) != 2:
            raise AssertionError("unique source marker was not located")
        source.write_text("MARK\nMARK\n")
        try:
            source_line(spec)
        except RuntimeError:
            pass
        else:
            raise AssertionError("duplicate source marker was accepted")
        source.write_text("before\nMARK\nafter\n")
        validate_line_table(spec, 2)
        fake_subprocess.check_output = lambda *_args, **_kwargs: "LineNumberTable:\n line 9: 0\n"
        try:
            validate_line_table(spec, 2)
        except RuntimeError:
            pass
        else:
            raise AssertionError("stale LineNumberTable was accepted")
        fake_subprocess.check_output = lambda *_args, **_kwargs: "LineNumberTable:\n line 2: 0\n"

        boundary = boundary_class(321, spec)
        boundary.line = 3448
        processes[-1].stdout.feed("Breakpoint hit: fixture.Class.start(), line=3,448 bci=1\n")
        boundary.wait_hit()
        if boundary.close()["hit"] is not True:
            raise AssertionError("grouped source line was not recorded as a breakpoint hit")
        if operations[-1] != ("remove", "tcp:54321") or boundary.reader.is_alive():
            raise AssertionError("successful JDB cleanup leaked its reader or forward")

        fake_subprocess.Popen = lambda *_args, **_kwargs: (_ for _ in ()).throw(OSError("spawn"))
        removals = operations.count(("remove", "tcp:54321"))
        try:
            boundary_class(321, spec)
        except OSError:
            pass
        else:
            raise AssertionError("JDB spawn failure was accepted")
        if operations.count(("remove", "tcp:54321")) != removals + 1:
            raise AssertionError("JDB spawn failure leaked its adb forward")

        def fail_writer(*_args, **_kwargs):
            process = FakeProcess(fail_write=True)
            processes.append(process)
            return process

        fake_subprocess.Popen = fail_writer
        removals = operations.count(("remove", "tcp:54321"))
        try:
            boundary_class(321, spec)
        except BrokenPipeError:
            pass
        else:
            raise AssertionError("JDB write failure was accepted")
        if processes[-1].returncode != 0 or operations.count(("remove", "tcp:54321")) != removals + 1:
            raise AssertionError("JDB write failure leaked its process or adb forward")
    print("input-process-jdb-unit-pass")


if __name__ == "__main__":
    main()
