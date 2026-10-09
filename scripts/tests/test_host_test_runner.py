"""Exercise actual discovery on new files, not a copy of the source gate's old list."""
import importlib.util
import io
from pathlib import Path
import sys
import tempfile
import unittest
import uuid

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("helix_host_test_runner", ROOT / "scripts/run-host-tests.py")
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class HostTestRunnerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.addCleanup(lambda: sys.path.remove(str(self.root)) if str(self.root) in sys.path else None)
        self.output = io.StringIO()

    def fixture(self, body):
        name = "test_new_contract_" + uuid.uuid4().hex
        (self.root / (name + ".py")).write_text(body)
        self.addCleanup(lambda: sys.modules.pop(name, None))

    def test_new_fixture_is_discovered_without_a_manifest_change(self):
        self.fixture("import unittest\nclass Fixture(unittest.TestCase):\n def test_new(self): pass\n")
        self.assertEqual(0, RUNNER.run(self.root, self.output))
        self.assertIn("Ran 1 test", self.output.getvalue())

    def test_failure_in_a_new_fixture_fails_the_gate(self):
        self.fixture("import unittest\nclass Fixture(unittest.TestCase):\n def test_new(self): self.fail('new regression')\n")
        self.assertEqual(1, RUNNER.run(self.root, self.output))
        self.assertIn("new regression", self.output.getvalue())

    def test_import_failure_fails_instead_of_skipping_the_file(self):
        self.fixture("raise ImportError('missing fixture dependency')\n")
        self.assertEqual(1, RUNNER.run(self.root, self.output))
        self.assertIn("missing fixture dependency", self.output.getvalue())

    def test_empty_and_all_skipped_suites_fail(self):
        self.assertEqual(1, RUNNER.run(self.root, self.output))
        self.fixture("import unittest\n@unittest.skip('optional')\nclass Fixture(unittest.TestCase):\n def test_new(self): pass\n")
        self.assertEqual(1, RUNNER.run(self.root, self.output))
        self.assertIn("every discovered test was skipped", self.output.getvalue())

    def test_import_interrupt_is_reported_as_failure(self):
        self.fixture("raise KeyboardInterrupt()\n")
        self.assertEqual(1, RUNNER.run(self.root, self.output))

    def test_execution_interrupt_is_not_converted_to_a_pass(self):
        self.fixture("import unittest\nclass Fixture(unittest.TestCase):\n def test_new(self): raise KeyboardInterrupt()\n")
        with self.assertRaises(KeyboardInterrupt):
            RUNNER.run(self.root, self.output)


if __name__ == "__main__":
    unittest.main()
