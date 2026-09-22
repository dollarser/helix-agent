#!/usr/bin/env python3
"""Union 214/215/216/218/219 regressions and recovery using the existing owned runner.

Run under with-host-slot after building both flavors. This integration wrapper uses scope216
for its storage gates and adds predecessor/UI coverage; it does not count duplicate methods.
"""
import importlib.util
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
spec = importlib.util.spec_from_file_location("conversation_integration", ROOT / "scripts/accept-conversation-interaction.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)

UI_CLASSES = ["com.helix.app.ui." + name for name in (
    "ConversationHeaderDeviceTest", "ConversationComposerDeviceTest", "ConversationTopBarDeviceTest",
    "ToolTimelineLayoutDeviceTest", "ModeLayoutDeviceTest", "ContextWindowDeviceTest", "SessionModelDeviceTest",
    "NavigationLayoutDeviceTest", "GroupedNavigationDeviceTest", "TaskLedgerProgressDeviceTest",
    "BackgroundTaskFlowDeviceTest", "ApprovalLayoutDeviceTest", "ChatCompactionFlowDeviceTest",
    "ChatStopProgressDeviceTest", "SessionForkFlowDeviceTest", "ConversationArtifactsDeviceTest",
    "ArtifactCenterDeviceTest",
)]
runner.SCOPE_CLASSES["216"] = list(dict.fromkeys(
    runner.SCOPE_CLASSES["both"] + runner.SCOPE_CLASSES["216"] + UI_CLASSES,
))
original_phases = runner.phases


def integrated_phases(scope, only, recovery_scenario=None):
    if scope != "216" or recovery_scenario is not None:
        raise ValueError("Integration requires --scope 216 without a single recovery scenario")
    result = original_phases(scope, only, recovery_scenario)
    if only in (None, "recovery"):
        result.extend(original_phases("both", "recovery"))
    return result


runner.phases = integrated_phases
if __name__ == "__main__":
    sys.exit(runner.main())
