package com.helix.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.eval.finishOwnedSkillModelRun
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

/** Explicit opt-in real model test; approves only its owned draft and exact installed snapshot. */
class SkillInstallerModelDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun realModelCreatesInstallsEnablesAndReads() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue(arguments.getString("skillInstallerModel") == "true")
            val model = requireNotNull(arguments.getString("model"))
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val container = app.appContainer
            val name = "install-model-${java.util.UUID.randomUUID()}"
            val directory = app.filesDir.toPath().resolve("workspaces/app/work/skills/$name")
            Files.createDirectories(directory)
            val path = "scope:app:work/skills/$name"
            val draft =
                ProviderDraft(
                    null,
                    "Skill Creator evaluation",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    NormalizedEndpoint.parse("http://10.0.2.2:30008/v1"),
                    model,
                    "{}",
                    false,
                )
            val chat = container.chatService
            val previousSession = chat.screen.value.openSessionId
            var provider: String? = null
            var session: String? = null
            try {
                val ownedProvider = container.providerService.create(draft, null)
                provider = ownedProvider
                check(
                    container.providerService.runConnectionTest(
                        ownedProvider,
                    ) is com.helix.provider.api.ProbeOutcome.Ok,
                )
                val ownedSession = chat.createSession("Skill Creator evaluation", ownedProvider, model)
                session = ownedSession
                val budgets = TurnBudgets(maxOutputTokens = 32768)
                chat.openTestSessionControl(ownedSession, AgentMode.ACT, budgets)
                chat.sendTestMessage(
                    ownedSession,
                    "Create a useful Skill for summarizing a local text file. Use write to create $path/SKILL.md. " +
                        "Its YAML name must be $name, with description and Markdown instructions. The parent exists. " +
                        "Then call skills.preview on $path and fix any errors. " +
                        "Then call skills.install with the exact preview hash. " +
                        "After approval enable this snapshot and read it with skills.read.",
                )
                awaitCreatedDraft(container, ownedSession, path)
                assertEquals(name, requireNotNull(container.skillAuthoringService).preview(path).name)
            } finally {
                finishOwnedSkillModelRun(container, session, provider, directory, name, previousSession)
            }
        }

    private fun isExactApproval(
        container: AppContainer,
        name: String,
        raw: String,
        path: String,
    ): Boolean {
        val args = Json.parseToJsonElement(raw).jsonObject
        val target = args["path"]?.jsonPrimitive?.content
        return when (name) {
            "write" -> {
                target == "$path/SKILL.md"
            }

            "skills.install" -> {
                target == path && args["expectedHash"]?.jsonPrimitive?.content ==
                    requireNotNull(container.skillAuthoringService).preview(path).snapshotHash
            }

            "skills.enable" -> {
                val ownName = path.substringAfterLast('/')
                val ownHash = requireNotNull(container.skillAuthoringService).preview(path).snapshotHash
                container.skillRepository.list().any {
                    it.key.name == ownName && it.key.snapshotHash == ownHash &&
                        args["source"]?.jsonPrimitive?.content == it.key.source.name &&
                        args["name"]?.jsonPrimitive?.content == it.key.name &&
                        args["snapshotHash"]?.jsonPrimitive?.content == it.key.snapshotHash
                }
            }

            else -> {
                false
            }
        }
    }

    @Suppress("NestedBlockDepth") // Polling stored approvals and terminal outcomes without replay.
    private fun awaitCreatedDraft(
        container: AppContainer,
        session: String,
        path: String,
    ) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 180000
        var complete = false
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val turn =
                container.storage.turns
                    .listBySession(session)
                    .lastOrNull()
            if (turn != null) {
                container.storage.toolCalls
                    .listByTurn(turn.id)
                    .filter { it.state == "AWAITING_APPROVAL" }
                    .forEach { call ->
                        val approval = container.storage.approvals.byToolCall(call.callId)
                        if (approval != null) {
                            if (isExactApproval(container, call.name, call.argsJson, path)) {
                                container.chatService.approveApproval(approval.id)
                            } else {
                                container.chatService.denyApproval(approval.id)
                            }
                        }
                    }
                if (TurnState.valueOf(turn.state).isTerminal) {
                    val calls = container.storage.toolCalls.listByTurn(turn.id)
                    for (tool in listOf("write", "skills.preview", "skills.install", "skills.enable", "skills.read")) {
                        assertTrue(
                            "missing $tool, error=${turn.errorCode}: ${calls.map { it.name to it.state }}",
                            calls.any { it.name == tool && it.state == "COMPLETED" },
                        )
                    }
                    complete = true
                    break
                }
            }
            Thread.sleep(100)
        }
        assertTrue("real model turn must finish", complete)
    }
}
