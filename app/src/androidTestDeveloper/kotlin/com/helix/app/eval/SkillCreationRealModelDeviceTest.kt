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
        val model = com.helix.app.provider.BuiltInModel.MODEL_ID
        check(model in provider.conversationModels)
        val trial = "skill-create-${UUID.randomUUID()}"
        val name = "created-${UUID.randomUUID()}"
        val previous = c.chatService.screen.value.openSessionId

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
        val requestGate =
            OwnedModelRequestGate(
                File(app.filesDir, "closed-loop-eval/$trial.budget"),
                name,
                createNew = true,
                bounded = false,
            )
        val factoryField =
            c.providerService.javaClass
                .getDeclaredField("factory")
                .apply { isAccessible = true }
        val factory = factoryField.get(c.providerService)
        val wireField = factory.javaClass.getDeclaredField("builtInWire").apply { isAccessible = true }
        val originalWire = wireField.get(factory) as com.helix.app.account.BuiltInAccountWire
        val delegate = originalWire.newInvocation()
        wireField.set(
            factory,
            object : com.helix.provider.api.wire.WireClient {
                override suspend fun open(
                    request: com.helix.provider.api.wire.WireRequest,
                ): com.helix.provider.api.wire.WireResponse {
                    requestGate.admit(request)
                    return delegate.open(request)
                }
            },
        )
        try {
            check(c.profileStore.profile == com.helix.core.model.SafetyProfile.ADVANCED)
            c.sessionPermissionEdit.saveSessionConfig(
                session,
                SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                System.currentTimeMillis(),
            )
            withTimeout(30_000) { configureOwnedSession(c, session) }
            val prompt =
                """
                Owner-authorized synthetic task, only this session workspace. Create a usable Skill named $name
                directly using existing file tools in .agents/skills/$name (SKILL.md plus references/guide.md).
                Description: copy a local challenge into a result. Its instructions must tell the caller to read
                input/challenge.txt and write its exact content to output/skill-result.txt. Put a short explanation
                in references/guide.md. Also create scripts/copy.py using only Python's pathlib to perform
                that copy. Do not use a manual authoring UI.
                Validate and install the exact content with existing Skill tools, enable for this session, verify
                discovery, then actually read the installed Skill instructions and its reference and use
                skills.run to execute the installed scripts/copy.py now.
                Verify output/skill-result.txt by reading it. No external downloads or accounts.
                Finish with a concise result.
                """.trimIndent()
            val admission = c.chatService.sendSubmission(ChatSubmission(session, 0, trial, prompt)).await()
            check(admission.outcome is ChatSubmissionOutcome.Accepted)
            while (true) {
                val turn =
                    c.storage.turns
                        .listBySession(session)
                        .lastOrNull()
                if (turn != null && TurnState.valueOf(turn.state).isTerminal) break
                delay(200)
            }
            val turn =
                requireNotNull(
                    c.storage.turns
                        .listBySession(session)
                        .lastOrNull(),
                )
            check(TurnState.valueOf(turn.state).isTerminal)
            val calls = c.storage.toolCalls.listByTurn(turn.id)
            val requiredTools =
                listOf(
                    "skills.preview",
                    "skills.install",
                    "skills.enable",
                    "skills.list",
                    "skills.read",
                    "skills.read_resource",
                    "skills.run",
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
            finishOwnedCase(c.storage, c.chatService, session, requestGate::close, {
                saveClosedLoopEvidence(
                    app,
                    c,
                    ClosedLoopCaseEvidence(
                        session,
                        "skill-creation",
                        trial,
                        model,
                        started,
                        passed,
                        failure,
                    ),
                )
            }, { stopped ->
                // Keep the closed guard installed if durable Stop could not be confirmed.
                restoreStoppedWire(wireField, factory, originalWire, stopped)
                if (previous == null) c.chatService.closeSession() else c.chatService.openSession(previous)
            })
        }
        assertTrue("Real model Skill creation failed; synthetic evidence retained: $failure", passed)
    }

    private fun restoreStoppedWire(
        field: java.lang.reflect.Field,
        factory: Any,
        original: com.helix.app.account.BuiltInAccountWire,
        stopped: Boolean,
    ) {
        if (stopped) field.set(factory, original)
    }

    private suspend fun configureOwnedSession(
        c: com.helix.app.AppContainer,
        session: String,
    ) {
        configureOwnedModelSession(c.storage, c.chatService, session, AgentMode.ACT)
    }

    private fun selectProvider(
        c: com.helix.app.AppContainer,
        requested: String?,
    ) = c.providerService.rows.value.firstOrNull { row ->
        val config = c.storage.providerConfigs.find(row.id)
        val endpoint = config?.endpoint.orEmpty()
        require(requested == com.helix.app.provider.BuiltInModel.PROVIDER_ID)
        row.id == requested && row.chatSelectable &&
            row.conversationModels.isNotEmpty() && !endpoint.contains("127.0.0.1") &&
            !endpoint.contains("10.0.2.2") && !endpoint.contains("example.invalid")
    } ?: error("No existing selectable real model provider available")
}
