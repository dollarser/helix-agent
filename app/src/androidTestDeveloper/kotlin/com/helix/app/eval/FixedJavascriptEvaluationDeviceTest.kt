package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import com.helix.app.provider.ProviderDraft
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Executes the fixed JavaScript cases through the production model, approval and isolated runtime loop. */
class FixedJavascriptEvaluationDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val directory get() = File(app.filesDir, "hxa100")
    private val resolvedApprovals = mutableSetOf<String>()
    private val cancelledSessions = mutableSetOf<String>()

    @Test
    fun fixedJavascriptThroughTheProductionTurnLoop() =
        runBlocking {
            assumeTrue(
                androidx.test.platform.app.InstrumentationRegistry
                    .getArguments()
                    .getString("helix.eval") == "true",
            )
            val config = Json.parseToJsonElement(File(directory, "config.json").readText()).jsonObject
            val corpus = File(directory, "fixed-evals.tsv").readBytes()
            require(hash(corpus) == "f27bf8b51e61be248a6e642c22cefc3e5045d0d35e37518377eb9b8cdf85e795")
            val rows = corpus.toString(Charsets.UTF_8).lines().filter { it.startsWith("js-") }
            val providers = mutableMapOf<ProviderProtocol, String>()
            val previous = container.chatService.runControl.value
            try {
                rows.forEach { line ->
                    val cells = line.split('\t')
                    val protocol = ProviderProtocol.valueOf(cells[3])
                    val model = config.getValue("model").jsonPrimitive.content
                    val provider =
                        providers[protocol] ?: createProvider(protocol, model)
                            .also { providers[protocol] = it }
                    runCase(cells, provider, config.getValue("model").jsonPrimitive.content)
                }
            } finally {
                container.chatService.stop()
                container.chatService.closeSession()
                container.chatService.setMode(previous.mode)
                container.chatService.setTurnBudgets(previous.budgets)
                providers.values.forEach { container.providerService.delete(it) }
            }
        }

    private suspend fun createProvider(
        protocol: ProviderProtocol,
        model: String,
    ): String {
        val draft =
            ProviderDraft(
                null,
                "HXA-100 JavaScript",
                protocol,
                NormalizedEndpoint.parse("http://10.0.2.2:${evaluationProviderPort()}/v1"),
                model,
                "{}",
                false,
                CleartextAuthorization("10.0.2.2", evaluationProviderPort()),
                emptyList(),
            )
        val id = container.providerService.create(draft, null, cleartextConfirmed = true)
        val probe = container.providerService.runConnectionTest(id)
        check(probe is ProbeOutcome.Ok) { "real provider probe failed: $probe" }
        return id
    }

    private suspend fun runCase(
        cells: List<String>,
        provider: String,
        model: String,
    ) {
        val session = container.chatService.createSession("fixed-${cells[0]}", provider, model)
        val context =
            "This is an isolated JavaScript evaluation. Call code.javascript.run exactly once " +
                "with this exact helixMain body, without rewriting it: " + code(cells[0]) +
                " After the tool result, describe its actual outcome without retrying. " +
                "For cancellation, the host will press Stop after execution begins."
        container.storage.messages.append("$session-context", session, null, "USER", "TEXT", context)
        container.storage.messages.append(
            "$session-ack",
            session,
            null,
            "ASSISTANT",
            "TEXT",
            "Evaluation context received.",
        )
        try {
            container.chatService.openSession(session)
            container.chatService.setMode(AgentMode.valueOf(cells[2]))
            container.chatService.setTurnBudgets(TurnBudgets(8, 6, 131072, 4096, 131072))
            container.chatService.send(if (cells[0] == "js-004") JS_CANCEL_SETUP else cells[4])
            awaitTurn(session, cells[0])
            saveResult(cells, session, context)
        } finally {
            container.chatService.stop()
            container.chatService.closeSession()
        }
    }

    private fun awaitTurn(
        session: String,
        caseId: String,
    ) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 180_000L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val turn =
                container.storage.turns
                    .listBySession(session)
                    .lastOrNull()
            if (turn != null) {
                val calls = container.storage.toolCalls.listByTurn(turn.id)
                calls.forEach { resolveApproval(it, caseId) }
                if (caseId == "js-004" &&
                    calls.any {
                        container.storage.approvals
                            .byToolCall(it.callId)
                            ?.consumedAt != null
                    } &&
                    cancelledSessions.add(session)
                ) {
                    Thread.sleep(2_000L)
                    container.chatService.stop()
                }
                if (TurnState.valueOf(turn.state).isTerminal) return
            }
            Thread.sleep(100)
        }
        error("fixed file case timed out: $caseId")
    }

    @Suppress("ReturnCount") // ignore absent, non-pending and already submitted decisions
    private fun resolveApproval(
        call: ToolCallEntity,
        caseId: String,
    ) {
        if (call.state != "AWAITING_APPROVAL") return
        val approval = container.storage.approvals.byToolCall(call.callId) ?: return
        if (!resolvedApprovals.add(approval.id)) return
        val args = Json.parseToJsonElement(call.argsJson).jsonObject
        val exactCode =
            call.name == "code.javascript.run" &&
                args["code"]?.jsonPrimitive?.content == code(caseId)
        if (exactCode) {
            container.chatService.approveApproval(approval.id)
        } else {
            container.chatService.denyApproval(approval.id)
        }
    }

    private fun code(id: String): String =
        when (id) {
            "js-001" -> {
                "return {sum: 1 + 2 + 3};"
            }

            "js-002", "js-004" -> {
                "while (true) {}"
            }

            "js-003" -> {
                "const result = {}; " +
                    "try { fetch('https://example.test'); } catch (e) { result.fetch = e.name; } " +
                    "try { require('fs'); } catch (e) { result.require = e.name; } return result;"
            }

            else -> {
                error("unknown case")
            }
        }

    private fun verifyCase(
        id: String,
        calls: List<ToolCallEntity>,
        turnState: String,
    ): Boolean {
        val call = calls.singleOrNull()
        val result = call?.let { container.storage.toolResults.byToolCall(it.callId) }
        val approved =
            call?.let {
                container.storage.approvals
                    .byToolCall(it.callId)
                    ?.decision == "APPROVED"
            }
        if (call == null || result == null || approved != true) return false
        return call.name == "code.javascript.run" &&
            verifyJavascriptOutcome(
                id,
                call.state,
                turnState,
                result,
                container.storage.toolResults
                    .readContent(result)
                    .orEmpty(),
                javascriptAuditCode(container, call.callId),
            )
    }

    private fun saveResult(
        cells: List<String>,
        session: String,
        context: String,
    ) {
        val turn =
            container.storage.turns
                .listBySession(session)
                .last()
        val calls = container.storage.toolCalls.listByTurn(turn.id)
        val text =
            container.storage.messages
                .listBySession(session)
                .filter { it.role == "ASSISTANT" && it.kind == "TEXT" && it.turnId == turn.id }
                .mapNotNull { container.storage.messages.readContent(it) }
                .joinToString("\n")
        val passed = verifyCase(cells[0], calls, turn.state)
        val config = Json.parseToJsonElement(File(directory, "config.json").readText()).jsonObject
        val result =
            buildJsonObject {
                put("id", cells[0])
                put("inputRoute", if (cells[0] == "js-004") "host_stop_after_real_model_tool_start" else "model_turn")
                if (cells[0] == "js-004") put("setupPromptSha256", hash(JS_CANCEL_SETUP.toByteArray()))
                put("protocol", cells[3])
                put("provider", config.getValue("provider"))
                put("providerReportedVersion", config.getValue("providerReportedVersion"))
                put("toolVersions", exposedEvaluationTools(container, AgentMode.valueOf(cells[2])))
                put("temperature", kotlinx.serialization.json.JsonNull)
                put("temperatureSource", "provider_default_not_overridden")
                put("dateUtc", config.getValue("dateUtc"))
                put("result", if (passed) "PASS" else "FAIL")
                put("model", config.getValue("model"))
                put("gitCommit", config.getValue("gitCommit"))
                put(
                    "date",
                    java.time.Instant
                        .now()
                        .toString(),
                )
                put("api", android.os.Build.VERSION.SDK_INT)
                put("device", evaluationDevice())
                put("datasetSha256", hash(File(directory, "fixed-evals.tsv").readBytes()))
                put("promptSha256", hash(cells[4].toByteArray()))
                put("fixtureContextSha256", hash(context.toByteArray()))
                put("turnState", turn.state)
                put("errorCode", turn.errorCode)
                put("elapsedMs", (turn.endedAt ?: System.currentTimeMillis()) - turn.startedAt)
                put("text", text)
                put("calls", JsonArray(calls.map { JsonPrimitive("${it.name}:${it.state}") }))
                put("toolResults", results(calls))
            }
        File(directory, "${cells[0]}.json").writeText(result.toString())
        assertTrue("${cells[0]} failed: $result", passed)
    }

    private fun results(calls: List<ToolCallEntity>): JsonArray =
        JsonArray(
            calls.map { call ->
                val result = container.storage.toolResults.byToolCall(call.callId)
                buildJsonObject {
                    put("name", call.name)
                    put("auditCode", javascriptAuditCode(container, call.callId))
                    put("status", result?.status)
                    put("summary", result?.summary)
                    put("content", result?.let { container.storage.toolResults.readContent(it) })
                }
            },
        )

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private fun decodedResult(content: String): String =
    Json
        .parseToJsonElement(content)
        .jsonObject
        .getValue("result")
        .jsonPrimitive.content

private fun verifyJavascriptOutcome(
    id: String,
    callState: String,
    turnState: String,
    result: com.helix.core.storage.entity.ToolResultEntity,
    content: String,
    auditCode: String?,
): Boolean =
    when (id) {
        "js-001" -> {
            callState == "COMPLETED" && decodedResult(content) == "{\"sum\":6}"
        }

        "js-002" -> {
            result.status == "FAILED" && result.summary ==
                "tool exceeded its deadline; the stable timeout error is the model-visible outcome"
        }

        "js-003" -> {
            callState == "COMPLETED" && content.contains("ReferenceError") &&
                content.contains("fetch") && content.contains("require")
        }

        "js-004" -> {
            turnState == "CANCELLED" && auditCode == "CANCELLED_AFTER_START"
        }

        else -> {
            false
        }
    }

private const val JS_CANCEL_SETUP = "Start the JavaScript calculation using the exact fixture code now."

private fun javascriptAuditCode(
    container: com.helix.app.AppContainer,
    callId: String,
): String? =
    container.storage.auditEvents
        .recent(1_000)
        .asSequence()
        .filter { it.correlationId == callId }
        .mapNotNull {
            Json
                .parseToJsonElement(it.redactedPayload)
                .jsonObject["code"]
                ?.jsonPrimitive
                ?.content
        }.firstOrNull()
