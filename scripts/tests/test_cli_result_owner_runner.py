import importlib.util
from pathlib import Path
import sys
import unittest

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location("cli_result_owner", SCRIPTS / "run-cli-result-owner-kill.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class ComponentStateTest(unittest.TestCase):
    def test_default_and_explicit_states_are_distinguished(self):
        for label, expected in [("enabledComponents", "enabled"), ("disabledComponents", "disabled")]:
            dump = f"  User 0: installed=true enabled=0\n    {label}:\n      {runner.SERVICE}\n"
            self.assertEqual(expected, runner.component_state(dump))
        self.assertEqual("default", runner.component_state("  User 0: installed=true enabled=0\n"))

    def test_adjacent_component_sections_do_not_bleed(self):
        dump = (
            "  User 0: installed=true enabled=0\n"
            "    enabledComponents:\n      unrelated.Service\n"
            f"    disabledComponents:\n      {runner.SERVICE}\n"
        )
        self.assertEqual("disabled", runner.component_state(dump))

    def test_other_user_cannot_supply_the_state(self):
        dump = (
            "  User 0: installed=true enabled=0\n"
            f"  User 10: installed=true enabled=0\n    disabledComponents:\n      {runner.SERVICE}\n"
        )
        self.assertEqual("default", runner.component_state(dump))
        with self.assertRaises(RuntimeError):
            runner.component_state("  User 10: installed=true enabled=0\n")


if __name__ == "__main__":
    unittest.main()
