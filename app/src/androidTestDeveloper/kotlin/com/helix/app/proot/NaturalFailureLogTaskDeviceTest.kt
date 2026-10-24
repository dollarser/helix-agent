package com.helix.app.proot

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.application.tools.ToolFailureContent
import com.helix.app.provider.BuiltInModel
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.ProviderId
import com.helix.core.model.SessionId
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual failed PRoot preparation, then an ordinary natural-language Agent task; no forced model tool steps. */
class NaturalFailureLogTaskDeviceTest {
    @Test
    @Suppress("LongMethod") // One bounded task and original-execution invariants are kept together.
    fun modelDiagnosesOriginalFailureThroughProductionRequestContext(): Unit =
        runBlocking {
            val flag = InstrumentationRegistry.getArguments().getString("naturalDiscovery")
            assumeTrue("Explicit live authorization absent", flag != null)
            require(flag == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            require(app.packageName.endsWith(".asrvalidation"))
            val c = app.awaitAppContainer()
            assertTrue("Account restoration must complete before starting model tasks", app.account.signedIn.value)
            val fixture = InPlaceLogCloseoutDeviceTest.Fixture(c.storage)
            var active: String? = null
            try {
                val record = fixture.run("printf SYNTH_NATURAL_ERROR_731 >&2; exit 7")
                fixture.persist(record)
                val failed =
                    com.helix.core.model.ToolDispatchOutcome.ExecutionFailed(
                        com.helix.core.model.DispatchOutcomeCode.TOOL_FAILED,
                        terminalFailure(
                            record,
                            "Original command failed; inspect stderr",
                            "JOB_FAILED",
                            requiresReview = false,
                        ).detail,
                    )
                c.storage.toolResults.append(
                    "natural-original-result",
                    "row",
                    "FAILED",
                    "Original failed",
                    com.helix.app.application.tools.ToolFailureContent
                        .encode(failed),
                )
                val original = c.storage.turns.resolve("turn")
                val building = c.storage.turns.updateState(original, TurnState.BUILDING_CONTEXT, 0, null, null)
                c.storage.turns.updateState(building, TurnState.FAILED, 0, System.currentTimeMillis(), "TOOL_FAILED")
                c.storage.sessions.bindProvider("session", BuiltInModel.PROVIDER_ID, BuiltInModel.MODEL_ID)
                c.storage.sessionPermissionConfigs.setForSession(
                    "session",
                    SessionPermissionConfig.of(SessionPermissionMode.READ_ONLY),
                    System.currentTimeMillis(),
                )
                val allowed = setOf("tools.search", "tools.read_result", ProotLogTool.NAME)
                c.toolPipeline.registry
                    .all()
                    .distinctBy { it.origin.canonicalOf() to it.name }
                    .filter {
                        it.name.value !in
                            allowed
                    }.forEach {
                        c.storage.toolAvailability.set(
                            it.origin.canonicalOf(),
                            it.name.value,
                            ToolAvailabilityScope.SESSION,
                            "session",
                            ToolAvailabilityState.DISABLED,
                            System.currentTimeMillis(),
                        )
                    }
                active =
                    c.agentRuntime
                        .submit(
                            SubmitTurnCommand(
                                SessionId("session"),
                                ProviderId(BuiltInModel.PROVIDER_ID),
                                AgentMode.ACT,
                                "上一次执行失败。请查看它保存的错误日志，说明实际错误标记与退出码，不要重新执行命令。" +
                                    "原始日志引用为 turn/call。",
                                TurnBudgets(2048),
                                clientRequestId = UUID.randomUUID().toString(),
                                directUserRequest = true,
                            ),
                        ).value
                val id = requireNotNull(active)
                val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
                while (!TurnState
                        .valueOf(
                            c.storage.turns
                                .resolve(id)
                                .state,
                        ).isTerminal
                ) {
                    if (c.storage.modelCalls
                            .listByTurn(id)
                            .size >= 6 ||
                        android.os.SystemClock.elapsedRealtime() >= deadline
                    ) {
                        c.chatService.stopTask(id)
                        break
                    }
                    delay(100)
                }
                withTimeout(15_000) {
                    while (!TurnState
                            .valueOf(
                                c.storage.turns
                                    .resolve(id)
                                    .state,
                            ).isTerminal
                    ) {
                        delay(50)
                    }
                }
                val calls = c.storage.toolCalls.listByTurn(id)
                val answer =
                    c.storage.messages
                        .listBySession("session")
                        .filter {
                            it.turnId == id && it.role == "ASSISTANT" &&
                                it.kind == "TEXT"
                        }.joinToString("\n") {
                            c.storage.messages
                                .readContent(it)
                                .orEmpty()
                        }
                File(app.cacheDir, "natural-log-task.json").writeText(
                    buildJsonObject {
                        put(
                            "state",
                            c.storage.turns
                                .resolve(id)
                                .state,
                        )
                        put("answer", answer)
                        put("originalSubmissions", fixture.submissions)
                        put(
                            "modelCalls",
                            c.storage.modelCalls
                                .listByTurn(id)
                                .size,
                        )
                        put(
                            "catalog",
                            c.toolPipeline.mcpDiscovery
                                .catalog("session")
                                .diagnosticJson(),
                        )
                        put(
                            "modelToolHistory",
                            JsonArray(
                                com.helix.app.agent.ChatHistoryBuilder
                                    .toModelMessagesStrict(
                                        c.storage.messages.listBySession("session").filter { it.turnId == id }.map {
                                            com.helix.app.agent.ChatHistoryBuilder.PersistedRow(
                                                it.turnId,
                                                it.role,
                                                it.kind,
                                                c.storage.messages.readContent(it),
                                            )
                                        },
                                    ).filter { it.role == com.helix.core.model.ModelRole.TOOL }
                                    .map {
                                        JsonPrimitive(
                                            it.text,
                                        )
                                    },
                            ),
                        )
                        put(
                            "calls",
                            JsonArray(
                                calls.map { call ->
                                    buildJsonObject {
                                        put("name", call.name)
                                        put("arguments", call.argsJson)
                                        put("state", call.state)
                                        put(
                                            "result",
                                            c.storage.toolResults
                                                .byToolCall(call.id)
                                                ?.let(c.storage.toolResults::readContent),
                                        )
                                    }
                                },
                            ),
                        )
                    }.toString(),
                )
                assertEquals(
                    "COMPLETED",
                    c.storage.turns
                        .resolve(id)
                        .state,
                )
                assertTrue(calls.any { it.name == ProotLogTool.NAME && it.state == "COMPLETED" })
                assertTrue(answer.contains("SYNTH_NATURAL_ERROR_731"))
                assertTrue(
                    Regex("(?:退出码|exit(?:\\s+code|Code))\\D{0,16}7(?!\\d)", RegexOption.IGNORE_CASE)
                        .containsMatchIn(answer),
                )
                assertEquals(1, fixture.submissions)
                assertEquals(
                    "FAILED",
                    c.storage.toolCalls
                        .resolve("row")
                        .state,
                )
            } finally {
                active?.let(c.chatService::stopTask)
                fixture.close()
            }
        }
}
