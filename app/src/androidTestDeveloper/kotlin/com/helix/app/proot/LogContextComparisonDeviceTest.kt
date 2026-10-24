package com.helix.app.proot

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.app.chat.ToolFailureContent
import com.helix.app.provider.BuiltInModel
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.ProviderId
import com.helix.core.model.SessionId
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.provider.api.wire.WireClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Two bounded observations, not a replacement production acceptance or a prompt-tuning loop. */
class LogContextComparisonDeviceTest {
    @Test fun minimalProjectionKeepsCurrentPairsAndSchemas() {
        val body =
            Json
                .parseToJsonElement(
                    """{"messages":[{"role":"system","content":"old policy"},
            {"role":"user","content":"old task"},{"role":"assistant","content":"old answer"},
            {"role":"user","content":"$LOG_COMPARISON_PROMPT"},
            {"role":"assistant","tool_calls":[{"id":"a"}]},
            {"role":"tool","tool_call_id":"a","content":"untrusted result"}],
            "tools":[{"name":"same-schema"}],"model":"same-model","tool_choice":"auto"}""",
                ).jsonObject
        val minimal = minimalLogContext(body, "same catalog")
        assertEquals(body["tools"], minimal["tools"])
        assertEquals(body["model"], minimal["model"])
        assertEquals(body["tool_choice"], minimal["tool_choice"])
        assertEquals(
            (body.getValue("messages") as JsonArray).takeLast(3),
            (minimal.getValue("messages") as JsonArray).drop(1),
        )
    }

    @Test
    @Suppress("LongMethod") // One shared original operation, fixed two-condition experiment and unconditional cleanup.
    fun compareFixedFailureWithoutForcingToolSteps(): Unit =
        runBlocking {
            val flag = InstrumentationRegistry.getArguments().getString("logContextComparison")
            assumeTrue("Explicit live comparison absent", flag != null)
            require(flag == "true")
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            require(app.packageName.endsWith(".asrvalidation"))
            val c = app.awaitAppContainer()
            check(app.account.signedIn.value)
            val directory = File(app.cacheDir, "log-context-comparison").apply { check(mkdirs() || isDirectory) }
            val fixture = InPlaceLogCloseoutDeviceTest.Fixture(c.storage)
            val factoryField =
                c.providerService.javaClass
                    .getDeclaredField("factory")
                    .apply { isAccessible = true }
            val factory = factoryField.get(c.providerService)
            val wireField = factory.javaClass.getDeclaredField("builtInWire").apply { isAccessible = true }
            val originalWire = wireField.get(factory) as WireClient
            val rows = mutableListOf<JsonObject>()
            try {
                prepare(c, fixture)
                for (minimal in listOf(false, true)) {
                    resetExposure(c)
                    val label = if (minimal) "minimal" else "full"
                    val caseDirectory = File(directory, label).apply { check(mkdirs() || isDirectory) }
                    val wire =
                        LogComparisonWire(originalWire, caseDirectory) { tools ->
                            c.toolPipeline.mcpDiscovery
                                .catalog("session")
                                .modelContext(tools)
                        }.also { it.minimal = minimal }
                    wireField.set(factory, wire)
                    val turn =
                        c.agentRuntime
                            .submit(
                                SubmitTurnCommand(
                                    SessionId("session"),
                                    ProviderId(BuiltInModel.PROVIDER_ID),
                                    AgentMode.ACT,
                                    LOG_COMPARISON_PROMPT,
                                    TurnBudgets(2048),
                                    clientRequestId = UUID.randomUUID().toString(),
                                    directUserRequest = true,
                                ),
                            ).value
                    try {
                        awaitBounded(c, turn)
                        rows += observation(c, turn, label, fixture.submissions, wire.calls)
                        File(caseDirectory, "result.json").writeText(rows.last().toString())
                    } finally {
                        c.chatService.stopTask(turn)
                    }
                }
                val first = Json.parseToJsonElement(File(directory, "full/request-0.json").readText()).jsonObject
                val second = Json.parseToJsonElement(File(directory, "minimal/request-0.json").readText()).jsonObject
                assertEquals(first.getValue("sent").jsonObject["tools"], second.getValue("sent").jsonObject["tools"])
                assertEquals(1, fixture.submissions)
                File(directory, "summary.json").writeText(
                    buildJsonObject {
                        put("cases", JsonArray(rows))
                        put("sameOriginalSubmissions", fixture.submissions)
                        put("sameInitialSchemas", true)
                        put(
                            "alternativeModel",
                            "Only the existing configured model is used; no new paid model enabled",
                        )
                        put(
                            "configuredModels",
                            JsonArray(
                                c.providerService.rows.value
                                    .filter { it.chatSelectable }
                                    .map { kotlinx.serialization.json.JsonPrimitive("${it.id}/${it.model}") },
                            ),
                        )
                    }.toString(),
                )
            } finally {
                wireField.set(factory, originalWire)
                resetExposure(c)
                fixture.close()
            }
        }

    private fun prepare(
        c: AppContainer,
        f: InPlaceLogCloseoutDeviceTest.Fixture,
    ) {
        val record = f.run("printf SYNTH_NATURAL_ERROR_731 >&2; exit 7")
        f.persist(record)
        c.storage.toolResults.append(
            "comparison-result",
            "row",
            "FAILED",
            "Original failed",
            ToolFailureContent.encode(
                ToolDispatchOutcome.ExecutionFailed(
                    DispatchOutcomeCode.TOOL_FAILED,
                    terminalFailure(
                        record,
                        "Original command failed; inspect stderr",
                        "JOB_FAILED",
                        requiresReview = false,
                    ).detail,
                ),
            ),
        )
        val building =
            c.storage.turns.updateState(
                c.storage.turns.resolve("turn"),
                TurnState.BUILDING_CONTEXT,
                0,
                null,
                null,
            )
        c.storage.turns.updateState(building, TurnState.FAILED, 0, System.currentTimeMillis(), "TOOL_FAILED")
        c.storage.sessions.bindProvider("session", BuiltInModel.PROVIDER_ID, BuiltInModel.MODEL_ID)
        c.storage.sessionPermissionConfigs.setForSession(
            "session",
            SessionPermissionConfig.of(SessionPermissionMode.READ_ONLY),
            System.currentTimeMillis(),
        )
        c.toolPipeline.registry
            .all()
            .distinctBy { it.origin.canonicalOf() to it.name }
            .filter { it.name.value !in setOf("tools.search", "tools.read_result", ProotLogTool.NAME) }
            .forEach {
                c.storage.toolAvailability.set(
                    it.origin.canonicalOf(),
                    it.name.value,
                    ToolAvailabilityScope.SESSION,
                    "session",
                    ToolAvailabilityState.DISABLED,
                    System.currentTimeMillis(),
                )
            }
    }

    private suspend fun awaitBounded(
        c: AppContainer,
        turn: String,
    ) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 90_000
        while (!TurnState
                .valueOf(
                    c.storage.turns
                        .resolve(turn)
                        .state,
                ).isTerminal
        ) {
            if (c.storage.modelCalls
                    .listByTurn(turn)
                    .size >= 6 ||
                android.os.SystemClock.elapsedRealtime() >= deadline
            ) {
                c.chatService.stopTask(turn)
                break
            }
            delay(100)
        }
        withTimeout(15_000) {
            while (!TurnState
                    .valueOf(
                        c.storage.turns
                            .resolve(turn)
                            .state,
                    ).isTerminal
            ) {
                delay(50)
            }
        }
    }

    private fun observation(
        c: AppContainer,
        turn: String,
        label: String,
        submissions: Int,
        requests: Int,
    ): JsonObject {
        val calls = c.storage.toolCalls.listByTurn(turn)
        val answer =
            c.storage.messages
                .listBySession("session")
                .filter { it.turnId == turn && it.role == "ASSISTANT" && it.kind == "TEXT" }
                .joinToString("\n") {
                    c.storage.messages
                        .readContent(it)
                        .orEmpty()
                }
        val passed =
            c.storage.turns
                .resolve(turn)
                .state == "COMPLETED" &&
                calls.any { it.name == ProotLogTool.NAME && it.state == "COMPLETED" } &&
                answer.contains("SYNTH_NATURAL_ERROR_731") &&
                Regex("(?:退出码|exit(?:\\s+code|Code))\\D{0,16}7(?!\\d)", RegexOption.IGNORE_CASE).containsMatchIn(answer)
        return buildJsonObject {
            put("context", label)
            put("diagnosticOnly", label == "minimal")
            put("taskPassed", passed)
            put(
                "state",
                c.storage.turns
                    .resolve(turn)
                    .state,
            )
            put(
                "errorCode",
                c.storage.turns
                    .resolve(turn)
                    .errorCode,
            )
            put("answer", answer)
            put("requests", requests)
            put("originalSubmissions", submissions)
            put(
                "catalog",
                c.toolPipeline.mcpDiscovery
                    .catalog("session")
                    .diagnosticJson(),
            )
            put("calls", callEvidence(c, calls))
        }
    }

    private fun callEvidence(
        c: AppContainer,
        calls: List<com.helix.core.storage.entity.ToolCallEntity>,
    ) = JsonArray(
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
    )

    @Suppress("UNCHECKED_CAST") // Debug test-only cache reset, not permission or persisted state.
    private fun resetExposure(c: AppContainer) {
        val discovery = c.toolPipeline.mcpDiscovery
        synchronized(discovery) {
            for (name in listOf("loaded", "admittedWindows")) {
                val field = discovery.javaClass.getDeclaredField(name).apply { isAccessible = true }
                (field.get(discovery) as MutableMap<String, *>).remove("session")
            }
        }
    }
}
