package com.helix.app.eval

import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.internal.PrefsLineStore
import com.helix.app.openTestSessionControl
import com.helix.app.proot.ensureInstalledRuntime
import com.helix.app.runtimebridge.HostToolBridgeSettings
import com.helix.core.model.AgentMode
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillKey
import com.helix.feature.conversation.input.ChatSubmission
import com.helix.feature.conversation.input.ChatSubmissionOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Explicit opt-in, existing credentials only. Bounded synthetic tasks, independently checked effects. */
class ClosedLoopRealModelDeviceTest {
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer
    private val args get() = InstrumentationRegistry.getArguments()

    @Suppress("LongMethod", "TooGenericExceptionCaught") // Save failure evidence and restore settings.
    @Test
    fun executeOwnedCase() =
        runBlocking {
            org.junit.Assume.assumeTrue(args.getString("helixRealModel") == "true")
            val case = requireNotNull(args.getString("helixCase"))
            require(case in setOf("visual", "scripts", "handoff"))
            val trial = requireNotNull(args.getString("helixTrial"))
            require(trial.matches(Regex("[a-z0-9-]+")))
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            )
            val provider = requireNotNull(args.getString("helixProvider"))
            val model = requireNotNull(args.getString("helixModel"))
            check(
                c.storage.providerConfigs
                    .resolve(provider)
                    .model == model,
            )
            val previous = c.chatService.screen.value.openSessionId

            val bridge = HostToolBridgeSettings(app)
            val originalBridge = bridge.enabled
            val lines = PrefsLineStore(app, "helix-mobile-use", synchronous = true)
            val scope = lines.lines(MobileUseGrantStore.CONFIG_KEY)
            val execution = lines.lines(MobileUseGrantStore.EXECUTION_KEY)
            val plugin = c.pluginService.list().single { it.native?.pluginId == "mobile-use" }
            val session = c.chatService.createSession("Synthetic closed loop $trial", provider, model)
            val nonce = "owned-${UUID.randomUUID().toString().take(8)}"
            val binding = requireNotNull(c.storage.workspaces.binding(session))
            val root =
                c.storage.workspaces
                    .managedDirectory(binding.workspaceId)
                    .resolve(binding.relativePath)
                    .toFile()
            val source = File(app.cacheDir, "closed-loop-$trial").apply { mkdirs() }
            var skill: SkillKey? = null
            var failure: String? = null
            var passed = false
            val started = SystemClock.elapsedRealtime()
            try {
                check(c.profileStore.profile == com.helix.core.model.SafetyProfile.ADVANCED)
                c.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
                val fixture = InstrumentationRegistry.getInstrumentation().context.packageName
                c.pluginService.setEnabled(plugin.id, true)
                c.pluginService.catalog.select(session, plugin.id, case == "visual")
                val store = MobileUseGrantStore(lines::lines, lines::setLines, { null })
                store.configureGlobal(setOf(fixture), false, shareScreens = true)
                store.configureExecution(background = true, allowMoveFromMain = false)
                bridge.setEnabled(case == "scripts")
                if (case == "scripts") {
                    ensureInstalledRuntime(app)
                    skill = prepareSkill(source, session)
                }
                c.chatService.openTestSessionControl(session, AgentMode.ACT, TurnBudgets(4096))
                val prompt =
                    when (case) {
                        "visual" -> {
                            visualPrompt(fixture, nonce)
                        }

                        "scripts" -> {
                            scriptsPrompt(requireNotNull(skill), nonce)
                        }

                        else -> {
                            "Owned synthetic task, only this session workspace. Read input/manual.txt, " +
                                "then write its exact contents to output/after-manual.txt."
                        }
                    }
                val admission = c.chatService.sendSubmission(ChatSubmission(session, 0, "owned-$trial", prompt)).await()
                check(admission.outcome is ChatSubmissionOutcome.Accepted) {
                    "Synthetic input was not admitted: ${admission.outcome}"
                }
                if (case == "handoff") {
                    exerciseHumanReturn(
                        session,
                        (admission.outcome as ChatSubmissionOutcome.Accepted).turnId,
                        root,
                        nonce,
                    )
                }
                waitBounded(session)
                passed = verify(session, case, root, nonce)
            } catch (error: Exception) {
                failure = "${error.javaClass.simpleName}: ${error.message}"
            } finally {
                c.storage.turns
                    .listBySession(session)
                    .lastOrNull()
                    ?.let { c.chatService.stopTurn(it.id) }
                saveClosedLoopEvidence(
                    app,
                    c,
                    ClosedLoopCaseEvidence(session, case, trial, model, started, passed, failure),
                )
                lines.setLines(MobileUseGrantStore.CONFIG_KEY, scope)
                lines.setLines(MobileUseGrantStore.EXECUTION_KEY, execution)
                bridge.setEnabled(originalBridge)
                c.pluginService.setEnabled(plugin.id, plugin.enabled)
                skill?.let(c.skillRepository::removePermanentlyForPrivacy)
                source.deleteRecursively()
                if (previous != null) c.chatService.openSession(previous) else c.chatService.closeSession()
            }
            assertTrue("Synthetic $trial failed; inspect retained evidence: $failure", passed)
        }

    private suspend fun exerciseHumanReturn(
        session: String,
        original: String,
        root: File,
        nonce: String,
    ) {
        c.chatService.takeOverTask(session, original)
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline &&
            c.chatService.handoffPresentation(original)?.canReturn != true
        ) {
            delay(50)
        }
        check(c.chatService.handoffPresentation(original)?.canReturn == true) { "Original stop did not settle" }
        File(root, "input/manual.txt").apply {
            parentFile!!.mkdirs()
            writeText(nonce)
        }
        val receipt =
            c.chatService
                .returnHumanControl(
                    session,
                    original,
                    false,
                    "I created input/manual.txt. Read its current contents and finish output/after-manual.txt.",
                ).await()
        check(receipt is ChatSubmissionOutcome.Accepted && receipt.turnId != original)
        val duplicate = c.chatService.returnHumanControl(session, original, false, "duplicate").await()
        check(duplicate == ChatSubmissionOutcome.Rejected("HANDOFF_CHANGED"))
    }

    private suspend fun waitBounded(session: String) {
        val deadline = SystemClock.elapsedRealtime() + 240_000
        while (SystemClock.elapsedRealtime() < deadline) {
            c.chatService.toolVisionConsent.pending.value.filter { it.sessionId == session }.forEach {
                c.chatService.toolVisionConsent.respond(it.id, true)
            }
            val turn =
                c.storage.turns
                    .listBySession(session)
                    .lastOrNull()
            if (turn != null) {
                if (TurnState.valueOf(turn.state).isTerminal) return
                if (c.storage.modelCalls
                        .listByTurn(turn.id)
                        .size >= 16 ||
                    c.storage.toolCalls
                        .listByTurn(turn.id)
                        .size >= 100
                ) {
                    c.chatService.stopTurn(turn.id)
                    error("Synthetic case budget reached; no replay")
                }
            }
            delay(100)
        }
        error("Synthetic case timed out at 240 seconds")
    }

    private fun prepareSkill(
        source: File,
        session: String,
    ): SkillKey {
        File(source, "scripts").mkdirs()
        File(source, "SKILL.md").writeText(
            "---\nname: ${source.name}\ndescription: Owned synthetic delivery fixture\n---\n" +
                "Run scripts/deliver.py with the requested nonce. It writes output/skill.txt.\n",
        )
        File(source, "scripts/deliver.py").writeText(
            "import sys,os\nos.makedirs('output',exist_ok=True)\n" +
                "open('output/skill.txt','x').write(sys.argv[1])\nprint('SKILL_DELIVERED')\n",
        )
        val staged = c.skillImportService.stageDirectory(source.toPath())
        val snapshot = c.skillImportService.commit(staged, File(app.filesDir, "skills/snapshots").toPath())
        return c.skillRepository.registerSnapshot(snapshot).also {
            c.skillRepository.setEnabled(it, true, SkillEnablementScope.SESSION, session)
        }
    }

    private fun visualPrompt(
        fixture: String,
        nonce: String,
    ): String =
        "Owned synthetic test. Only operate $fixture in the selected BACKGROUND display and this session directory. " +
            "Launch that package. Observe ui.device and ui.snapshot, then take " +
            "ui.screenshot using that same display frame. " +
            "Use the actual image to locate and tap the PURPLE circle exactly once; the " +
            "colored circles have no semantic node. " +
            "Fill Background text with $nonce. Click Increment exactly once. Reobserve the actual counts and input. " +
            "Write output/result.txt containing exactly $nonce only after verifying " +
            "VISUAL_COUNT:1 and BACKGROUND_COUNT:1. " +
            "Do not open other apps, use OCR, inspect test source, run scripts, or " +
            "access main display. Finish in this conversation."

    private fun scriptsPrompt(
        key: SkillKey,
        nonce: String,
    ): String =
        "Owned synthetic test, only this session workspace. Demonstrate THREE " +
            "distinct script bridges by calling time.now " +
            "once via each: isolated code.javascript.run with toolBridge=true (helix.call), code.linux.run Python3 " +
            "(import helix; helix.call), and Node (import {call} from '/tmp/helix.mjs'; " +
            "await call). Use toolBridge=true; no native JS. " +
            "Then inspect and run the enabled owned Skill ${key.source}/${key.name}, " +
            "snapshotHash=${key.snapshotHash}, " +
            "path=scripts/deliver.py via skills.run with args=[$nonce] and artifacts=[output/skill.txt]. " +
            "Do not replace the Skill with an ad-hoc write. Read the produced file, " +
            "verify its exact contents, and deliver its artifact reference. " +
            "No network, accounts, other files, dependency installs, or repeated effectful calls."

    private fun verify(
        session: String,
        case: String,
        root: File,
        nonce: String,
    ): Boolean {
        val turn =
            c.storage.turns
                .listBySession(session)
                .last()
        if (turn.state != "COMPLETED") return false
        val calls = c.storage.toolCalls.listByTurn(turn.id)
        return when (case) {
            "scripts" -> {
                verifyScripts(turn.id, root, nonce)
            }

            "handoff" -> {
                File(root, "output/after-manual.txt").readText() == nonce &&
                    c.storage.turns
                        .listBySession(session)
                        .let { it.size == 2 && it.first().state == "CANCELLED" }
            }

            else -> {
                verifyVisual(calls, root, nonce)
            }
        }
    }

    private fun verifyScripts(
        turnId: String,
        root: File,
        nonce: String,
    ): Boolean {
        val calls = c.storage.toolCalls.listByTurn(turnId)
        val times = calls.count { it.name == "time.now" && it.state == "COMPLETED" }
        return File(root, "output/skill.txt").readText() == nonce && times == 3 &&
            calls.any { it.name == "skills.run" && it.state == "COMPLETED" } &&
            c.storage.artifacts.listByTurn(turnId).any {
                it.purpose == "OUTPUT" && it.relativePath.endsWith("output/skill.txt")
            }
    }

    private fun verifyVisual(
        calls: List<com.helix.core.storage.entity.ToolCallEntity>,
        root: File,
        nonce: String,
    ): Boolean {
        val uri = Uri.parse("content://${app.packageName}.test.capability-state/background")
        val effect =
            app.contentResolver.query(uri, null, null, null, null)!!.use {
                it.moveToFirst() && it.getInt(0) > 0 && it.getInt(1) == 1 && it.getInt(2) == 1
            }
        return effect && File(root, "output/result.txt").readText() == nonce &&
            calls.any { call ->
                call.name == "ui.screenshot" && c.storage.toolResults.byToolCall(call.callId)?.let { row ->
                    c.storage.toolResults
                        .readContent(row)
                        ?.contains("\"pixelsAttached\":true")
                } == true
            }
    }
}
