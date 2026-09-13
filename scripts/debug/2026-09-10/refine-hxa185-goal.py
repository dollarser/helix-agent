"""One-time source corrections after manual API review, not a test runner."""
from pathlib import Path
p=Path('app/src/androidTest/kotlin/com/helix/app/MainAppCombinedSoakDeviceTest.kt');s=p.read_text()
s=s.replace('''                chat.stop()
            }
            val settled = awaitTurn(session, block)
            requireTerminal(settled)
            if (success) {''','''                val activeTurn = requireNotNull(container.storage.turns.listBySession(session).lastOrNull())
                chat.stopTask(activeTurn.id, pause = true)
            }
            val settled = awaitTurn(session, block)
            if (success) {
                requireTerminal(settled)''')
s=s.replace('it.toolName == "goal.report"','it.name == "goal.report"')
s=s.replace('''                requireNotNull(modelServer).releaseFinalResponse()
            }
            val deadline''','''                check(settled?.pauseRequestedAt != null) { "missing durable user pause request" }
                requireNotNull(modelServer).releaseFinalResponse()
            }
            val deadline''')
s=s.replace('setOf("COMPLETED", "PAUSED", "FAILED", "CANCELLED")','setOf("COMPLETED", "PAUSED", "BLOCKED", "FAILED", "CANCELLED")')
s=s.replace('.put("goalSuccess", goalSuccess)', '.put("goalMechanism", "model-report-user-pause-v1")\n                .put("goalSuccess", goalSuccess)')
s=s.replace('even blocks PAUSED (Stop)', 'even blocks PAUSED (explicit user pause)')
s=s.replace('else blocks','else blocks')
p.write_text(s)
p=Path('scripts/run-ev04-mainapp-soak.py');s=p.read_text()
needle='    # ---- EV-04 business gate (b) : goal success/Stop parity (6/6 for 12 blocks)'
assert needle in s
s=s.replace(needle,'''    if done.get("goalMechanism") != "model-report-user-pause-v1":
        return ("FAIL_FUNCTIONAL", "missing/current Goal mechanism identity mismatch",
                "legacy Goal fixture is not new-main acceptance")

'''+needle)
p.write_text(s)
for name in ['scripts/test-run-ev04-mainapp-soak-logic.py','scripts/test-run-ev04-mainapp-soak-fixture-logic.py']:
 p=Path(name);s=p.read_text();s=s.replace('"goalSuccess":', '"goalMechanism": "model-report-user-pause-v1", "goalSuccess":');p.write_text(s)
