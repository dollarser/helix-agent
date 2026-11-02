package com.helix.app.eval

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.chat.ChatSubmission
import com.helix.app.chat.ChatSubmissionOutcome
import com.helix.core.model.AgentMode
import com.helix.core.model.SafetyProfile
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Owner opt-in: existing configured provider, one synthetic task, no credential extraction. */
class SkillCreationRealModelDeviceTest {
    @Test fun modelCreatesDiscoversAndUsesItsSkill() =
        runBlocking {
            require(InstrumentationRegistry.getArguments().getString("helixRealModel") == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            ActivityScenario.launch(MainActivity::class.java).use {
                withTimeout(30_000) {
                    app.appContainer.chatService.screen.first { state ->
                        state.openSessionId != null && !state.preparingDraft
                    }
                }
                runOwnedCase(app)
            }
        }

    @Suppress("LongMethod", "TooGenericExceptionCaught") // Preserve evidence and restore settings.
    private suspend fun runOwnedCase(app: HelixApplication) {
        val args = InstrumentationRegistry.getArguments()
        require(args.getString("helixRealModel") == "true")
        val c = app.appContainer
        check(
            c.storage.turns
                .listActive()
                .isEmpty(),
        )
        val provider = selectProvider(c, args.getString("helixProvider"))
        val model = provider.conversationModels.first()
        val trial = "skill-create-${UUID.randomUUID()}"
        val name = "created-${UUID.randomUUID()}"
        val previous = c.chatService.screen.value.openSessionId
        val profile = c.profileStore.profile
        val session = c.chatService.createSession("Synthetic Agent Skill creation", provider.id, model)
        val binding = requireNotNull(c.storage.workspaces.binding(session))
        val root =
            c.storage.workspaces
                .managedDirectory(binding.workspaceId)
                .resolve(binding.relativePath)
                .toFile()
        val expected = "SYNTHETIC_SKILL_${UUID.randomUUID()}"
        File(root, "input/challenge.txt").apply {
            parentFile!!.mkdirs()
            writeText(expected)
        }
        val started = SystemClock.elapsedRealtime()
        var passed = false
        var failure: String? = null
        try {
            c.profileStore.switchTo(SafetyProfile.ADVANCED)
            c.sessionPermissionEdit.saveSessionConfig(
                session,
                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                System.currentTimeMillis(),
            )
            configureOwnedSession(c, session)
            val prompt =
                """
                Owner-authorized synthetic task, only this session workspace. Create a usable Skill named $name
                directly using existing file tools in .agents/skills/$name (SKILL.md plus references/guide.md).
                Description: copy a local challenge into a result. Its instructions must tell the caller to read
                input/challenge.txt and write its exact content to output/skill-result.txt. Put a short explanation
                in references/guide.md. Do not execute scripts or use a manual authoring UI.
                Validate and install the exact content with existing Skill tools, enable for this session, verify
                discovery, then actually read the installed Skill instructions and its reference and use it now.
                Verify output/skill-result.txt by reading it. No external downloads or accounts.
                Finish with a concise result.
                """.trimIndent()
            val admission = c.chatService.sendSubmission(ChatSubmission(session, 0, trial, prompt)).await()
            check(admission.outcome is ChatSubmissionOutcome.Accepted)
            val deadline = SystemClock.elapsedRealtime() + 240_000
            while (SystemClock.elapsedRealtime() < deadline) {
                val turn =
                    c.storage.turns
                        .listBySession(session)
                        .lastOrNull()
                if (turn != null && TurnState.valueOf(turn.state).isTerminal) break
                if (turn != null) {
                    check(
                        c.storage.modelCalls
                            .listByTurn(turn.id)
                            .size <= 24,
                    ) { "Model request budget reached" }
                    check(
                        c.storage.toolCalls
                            .listByTurn(turn.id)
                            .size <= 100,
                    ) { "Tool budget reached" }
                }
                delay(200)
            }
            val turn =
                requireNotNull(
                    c.storage.turns
                        .listBySession(session)
                        .lastOrNull(),
                )
            check(TurnState.valueOf(turn.state).isTerminal) { "Model case exceeded 240 seconds" }
            val calls = c.storage.toolCalls.listByTurn(turn.id)
            val requiredTools =
                listOf(
                    "skills.preview",
                    "skills.install",
                    "skills.enable",
                    "skills.list",
                    "skills.read",
                    "skills.read_resource",
                )
            for (tool in requiredTools) {
                check(calls.any { it.name == tool && it.state == "COMPLETED" }) { "Missing actual completed $tool" }
            }
            check(c.skillRepository.list(session).any { it.key.name == name && it.enabled })
            check(File(root, ".agents/skills/$name/SKILL.md").isFile)
            check(File(root, "output/skill-result.txt").readText() == expected) { "File content differs" }
            passed = true
        } catch (error: Exception) {
            failure = "${error.javaClass.simpleName}: ${error.message}"
        } finally {
            c.storage.turns
                .listBySession(session)
                .lastOrNull()
                ?.let { c.chatService.stopTurn(it.id) }
            val evidence =
                ClosedLoopCaseEvidence(
                    session,
                    "skill-creation",
                    trial,
                    model,
                    started,
                    passed,
                    failure,
                )
            saveClosedLoopEvidence(app, c, evidence)
            c.profileStore.switchTo(profile)
            if (previous == null) c.chatService.closeSession() else c.chatService.openSession(previous)
        }
        assertTrue("Real model Skill creation failed; synthetic evidence retained: $failure", passed)
    }

    private suspend fun configureOwnedSession(
        c: com.helix.app.AppContainer,
        session: String,
    ) {
        val budgets = TurnBudgets(4096)
        c.chatService.openSession(session)
        withTimeout(10_000) { c.chatService.screen.first { it.openSessionId == session } }
        check(c.chatService.setModeFromComposer(session, AgentMode.ACT))
        c.chatService.setTurnBudgets(budgets)
        withTimeout(10_000) {
            c.chatService.runControl.first { it.mode == AgentMode.ACT && it.budgets == budgets }
        }
        check(
            c.storage.sessionRunControls
                .forSession(session)
                ?.budgets == budgets,
        )
    }

    private fun selectProvider(
        c: com.helix.app.AppContainer,
        requested: String?,
    ) = c.providerService.rows.value.firstOrNull { row ->
        val config = c.storage.providerConfigs.find(row.id)
        val endpoint = config?.endpoint.orEmpty()
        (requested == null || row.id == requested) && row.chatSelectable &&
            row.conversationModels.isNotEmpty() && !endpoint.contains("127.0.0.1") &&
            !endpoint.contains("10.0.2.2") && !endpoint.contains("example.invalid")
    } ?: error("No existing selectable real model provider available")
}
