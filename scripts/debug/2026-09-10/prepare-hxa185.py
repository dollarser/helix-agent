"""One-time source edit record. No builds, tests, adb calls, or running-process operations."""
from pathlib import Path
import hashlib
import json

root = Path.cwd()
backup = root / 'build/debug/2026-09-10/hxa185-before'
files = [
 'scripts/run-browser-autofill-soak.py',
 'app/src/androidTest/kotlin/com/helix/app/MainAppCombinedSoakDeviceTest.kt',
 'app/src/androidTest/kotlin/com/helix/app/provider/ScriptedTaskModelServer.kt',
 'feature/browser/src/androidTest/kotlin/com/helix/feature/browser/BrowserAutofillSoakDeviceTest.kt',
 'app/src/androidTest/kotlin/com/helix/app/diagnostics/ContinuousAppResourceDeviceTest.kt',
]
backup.mkdir(parents=True, exist_ok=False)
for name in files:
    target=backup/name
    target.parent.mkdir(parents=True,exist_ok=True)
    target.write_bytes((root/name).read_bytes())
(backup/'sha256.json').write_text(json.dumps({n:hashlib.sha256((root/n).read_bytes()).hexdigest() for n in files},indent=2))

p=root/files[0];s=p.read_text()
s=s.replace('import argparse\n','import argparse\nimport importlib.util\n')
marker='PACKAGE = "com.helix.feature.browser.test"'
helper='''_evidence_spec = importlib.util.spec_from_file_location(
    "helix_soak_evidence", pathlib.Path(__file__).resolve().parent / "debug/2026-09-10/soak_evidence.py",
)
_evidence = importlib.util.module_from_spec(_evidence_spec)
_evidence_spec.loader.exec_module(_evidence)

'''
s=s.replace(marker,helper+marker)
a=s.index('        m = re.search(r"Default Webview Implementation:');b=s.index('        memtotal =',a)
s=s[:a]+'''        (self.out / "webviewupdate-start.log").write_text(webview)
        ident.update(_evidence.parse_webview_identity(webview))
'''+s[b:]
s=s.replace('"progress.json", "soak-done.json"]','"progress.json", "soak-done.json", "autofill-failure.json"]')
p.write_text(s)

p=root/files[2];s=p.read_text()
s=s.replace('import java.util.concurrent.atomic.AtomicBoolean','import java.util.concurrent.CountDownLatch\nimport java.util.concurrent.TimeUnit\nimport java.util.concurrent.atomic.AtomicBoolean')
a=s.index('    /** The tool name emitted')
s=s[:a]+'''    @Volatile
    private var finalResponseGate: CountDownLatch? = null

    @Volatile
    var finalResponseRequested = CountDownLatch(1)
        private set

    fun releaseFinalResponse() {
        finalResponseGate?.countDown()
    }

'''+s[a:]
s=s.replace('        finalText: String = "Done.",\n    ) {','        finalText: String = "Done.",\n        holdFinalResponse: Boolean = false,\n    ) {\n        releaseFinalResponse()\n        finalResponseRequested = CountDownLatch(1)\n        finalResponseGate = if (holdFinalResponse) CountDownLatch(1) else null')
s=s.replace('        running.set(false)\n','        running.set(false)\n        releaseFinalResponse()\n')
s=s.replace('''        } else {
            textAnswerStream(armedFinalText)
        }
    }

    private fun writeJson''','''        } else {
            finalResponseRequested.countDown()
            val gate = finalResponseGate
            if (gate != null && !gate.await(30, TimeUnit.SECONDS)) {
                throw IOException("EV04 final response gate timed out")
            }
            textAnswerStream(armedFinalText)
        }
    }

    private fun writeJson''')
p.write_text(s)

p=root/files[1];s=p.read_text()
s=s.replace('import com.helix.core.model.CriterionVerificationBinding\n','').replace('import com.helix.core.model.CriterionVerificationMethod\n','')
s=s.replace('COMPLETED (tool evidence)','COMPLETED (model goal.report)').replace('COMPLETED via tool evidence','COMPLETED via goal.report')
a=s.index('                val criterion = chat.goalCriteria');b=s.index('                val path =',a);s=s[:a]+s[b:]
s=s.replace('''                        ScriptedTaskModelServer.Step("read") { JSONObject().put("path", path).toString() },
                    ),
                    "EV04 goal done''','''                        ScriptedTaskModelServer.Step("read") { JSONObject().put("path", path).toString() },
                        ScriptedTaskModelServer.Step("goal.report") {
                            JSONObject().put("status", "complete")
                                .put("summary", "EV04 goal $block: synthetic write/read completed").toString()
                        },
                    ),
                    "EV04 goal done''')
s=s.replace('''                requireNotNull(modelServer).arm(emptyList(), "EV04 goal no-tool $block")''','''                requireNotNull(modelServer).arm(
                    emptyList(), "EV04 goal held for user pause $block", holdFinalResponse = true,
                )''')
s=s.replace('''            requireTerminal(awaitTurn(session, block))
            val deadline = SystemClock.elapsedRealtime() + 10000''','''            if (!success) {
                // A real pending model request is the barrier: natural Turn completion is not Stop.
                val server = requireNotNull(modelServer)
                check(server.finalResponseRequested.await(20, TimeUnit.SECONDS)) {
                    "EV04 pause never reached a pending model request"
                }
                check(container.storage.goals.resolve(goalId).state == "RUNNING")
                chat.stop()
            }
            val settled = awaitTurn(session, block)
            requireTerminal(settled)
            if (success) {
                check(settled?.state == "COMPLETED") { "report Turn did not complete" }
                check(container.storage.toolCalls.listByTurn(requireNotNull(settled).id).any {
                    it.toolName == "goal.report" && it.state == "COMPLETED"
                }) { "no settled goal.report ToolCall" }
            } else {
                check(settled?.state == "CANCELLED") { "user Stop did not cancel the Turn: ${settled?.state}" }
                requireNotNull(modelServer).releaseFinalResponse()
            }
            val deadline = SystemClock.elapsedRealtime() + 10000''')
s=s.replace('''        } finally {
            runCatching {
                val stored = container.storage.goals.find(goalId)''','''        } finally {
            requireNotNull(modelServer).releaseFinalResponse()
            runCatching {
                val stored = container.storage.goals.find(goalId)''')
s=s.replace('Debug.getPss() / 1024','Debug.getPss()')
p.write_text(s)
