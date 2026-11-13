"""Core conversation selection and owned-run routing; never launches a device."""
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "acceptance_matrix", Path(__file__).resolve().parents[1] / "run-acceptance-matrix.py",
)
matrix = importlib.util.module_from_spec(spec)
spec.loader.exec_module(matrix)

CORE = (
    "ui.ProviderTurnJourneyDeviceTest",
    "localmodel.LocalProviderLoopDeviceTest",
    "infrastructure.agent.TurnDiagnosticStoreDeviceTest",
    "chat.ConversationStopConsistencyDeviceTest",
    "infrastructure.agent.RoomTurnLifecycleStoreDeviceTest",
    "chat.ToolSettlementRecoveryDeviceTest",
    "chat.SessionInputAdmissionFailureDeviceTest",
    "PermissionAtomicityDeviceTest",
    "chat.ToolInspectionQueryDeviceTest",
)


class CoreConversationMatrixTest(unittest.TestCase):
    def test_core_selects_whole_real_room_classes_for_both_channels(self):
        selectors, recovery = matrix.PRODUCT["core-conversation"]
        self.assertEqual(list(CORE), selectors)
        self.assertIsNone(recovery)
        for flavor in ("consumer", "developer"):
            methods, sources = matrix.methods_for(["com.helix.app." + name for name in selectors], flavor)
            self.assertEqual(len(set(methods)), len(methods))
            self.assertEqual({"com.helix.app." + name for name in CORE},
                             {method.split("#")[0] for method in methods})
            self.assertEqual(len(CORE), len(sources))
            self.assertIn("com.helix.app.infrastructure.agent.TurnDiagnosticStoreDeviceTest#"
                          "emptyToolCostDoesNotPreventModelStreaming", methods)
            self.assertTrue(all(len(digest) == 64 for digest in sources.values()))

    def test_plan_binds_current_methods_and_source_without_launching(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "plan"
            argv = ["matrix", "--scope", "206", "--group", "core-conversation", "--api", "36",
                    "--flavor", "developer", "--avd", "owned-api36", "--plan-only", "--output", str(output)]
            with patch.object(matrix.sys, "argv", argv), \
                    patch.object(matrix.subprocess, "check_output", return_value="fixture-commit\n"), \
                    patch.object(matrix.subprocess, "run") as launch:
                self.assertEqual(0, matrix.main())
                launch.assert_not_called()
            plan = json.loads((output / "developer-api36-core-conversation-expected.json").read_text())
            methods, sources = matrix.methods_for(["com.helix.app." + name for name in CORE], "developer")
            self.assertEqual(methods, plan["methods"])
            self.assertEqual(sources, plan["source_sha256"])
            self.assertEqual("fixture-commit", plan["commit"])

    def test_explicit_avd_requires_one_api_before_creating_output(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "must-not-exist"
            argv = ["matrix", "--scope", "206", "--group", "core-conversation",
                    "--avd", "owned-api36", "--plan-only", "--output", str(output)]
            with patch.object(matrix.sys, "argv", argv), \
                    self.assertRaisesRegex(ValueError, "exactly one --api"):
                matrix.main()
            self.assertFalse(output.exists())

    def test_observed_device_api_must_match_the_batch_label(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            device = output / "device.json"
            device.write_text(json.dumps({"api": 36}))
            matrix.verify_device_api(output, 36)
            for observed in (29, "36", None):
                device.write_text(json.dumps({"api": observed}))
                with self.subTest(observed=observed), self.assertRaisesRegex(ValueError, "does not match"):
                    matrix.verify_device_api(output, 36)

    def test_owned_runner_receives_explicit_avd_and_scoped_reset(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "run"
            argv = ["matrix", "--scope", "206", "--group", "core-conversation", "--api", "36",
                    "--flavor", "consumer", "--avd", "owned-api36", "--clear-app-data", "--clean-install",
                    "--first-port", "5666", "--output", str(output)]
            with patch.object(matrix.sys, "argv", argv), \
                    patch.object(matrix.subprocess, "check_output", return_value="fixture-commit\n"), \
                    patch.object(matrix.subprocess, "run", return_value=SimpleNamespace(returncode=1)) as launch:
                self.assertEqual(1, matrix.main())
            command = launch.call_args.args[0]
            self.assertEqual("scripts/run-owned-acceptance-emulator.py", command[1])
            self.assertEqual("owned-api36", command[command.index("--avd") + 1])
            self.assertEqual("5666", command[command.index("--port") + 1])
            self.assertIn("--clear-app-data", command)
            self.assertIn("--clean-install", command)
            self.assertEqual("com.helix.agent.test/com.helix.app.HelixAndroidJUnitRunner",
                             command[command.index("--runner") + 1])


if __name__ == "__main__":
    unittest.main()
