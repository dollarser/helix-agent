package com.helix.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.provider.api.CleartextAuthorization
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

/** Explicit opt-in real model test; approves only writing its newly owned draft manifest. */
class SkillCreatorModelDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun realModelWritesAndValidatesDraft() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            assumeTrue(arguments.getString("skillCreatorModel") == "true")
            val model = requireNotNull(arguments.getString("model"))
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val container = app.appContainer
            val name = "model-skill-${System.currentTimeMillis()}"
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
                    CleartextAuthorization("10.0.2.2", 30008),
                    emptyList(),
                )
            val provider = container.providerService.create(draft, null, cleartextConfirmed = true)
            val chat = container.chatService
            val previous = chat.runControl.value
            try {
                check(container.providerService.runConnectionTest(provider) is com.helix.provider.api.ProbeOutcome.Ok)
                val session = chat.createSession("Skill Creator evaluation", provider, model)
                chat.openSession(session)
                chat.setMode(AgentMode.ACT)
                chat.setTurnBudgets(TurnBudgets(12, 10, 524288, 32768, 557056))
                chat.send(
                    "Create a useful Skill for summarizing a local text file. Use write to create $path/SKILL.md. " +
                        "Its YAML name must be $name, with description and Markdown instructions. The parent exists. " +
                        "Then call skills.preview on $path and fix any errors. " +
                        "Do not install, enable or use external tools.",
                )
                awaitCreatedDraft(container, session, path)
                assertEquals(name, requireNotNull(container.skillAuthoringService).preview(path).name)
            } finally {
                chat.stop()
                chat.closeSession()
                chat.setMode(previous.mode)
                chat.setTurnBudgets(previous.budgets)
                container.providerService.delete(provider)
                Files.walk(directory).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
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
                            val target =
                                Json
                                    .parseToJsonElement(
                                        call.argsJson,
                                    ).jsonObject["path"]
                                    ?.jsonPrimitive
                                    ?.content
                            if (call.name == "write" &&
                                target == "$path/SKILL.md"
                            ) {
                                container.chatService.approveApproval(approval.id)
                            } else {
                                container.chatService.denyApproval(approval.id)
                            }
                        }
                    }
                if (TurnState.valueOf(turn.state).isTerminal) {
                    val calls = container.storage.toolCalls.listByTurn(turn.id)
                    assertTrue(
                        "turn=${turn.state}; error=${turn.errorCode}; calls=${calls.map { it.name to it.state }}",
                        calls.any {
                            it.name ==
                                "write" &&
                                it.state == "COMPLETED"
                        },
                    )
                    assertTrue(
                        "turn=${turn.state}; error=${turn.errorCode}; calls=${calls.map { it.name to it.state }}",
                        calls.any {
                            it.name ==
                                "skills.preview" &&
                                it.state == "COMPLETED"
                        },
                    )
                    complete = true
                    break
                }
            }
            Thread.sleep(100)
        }
        assertTrue("real model turn must finish", complete)
    }
}
