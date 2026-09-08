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

/** Exercises fixed read-only planning cases through the production mode filter and tool loop. */
class FixedPlanEvaluationDeviceTest {
    @get:Rule val activity = ActivityScenarioRule(MainActivity::class.java)
    private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val directory get() = File(app.filesDir, "hxa100")
    private val resolvedApprovals = mutableSetOf<String>()

    @Test
    fun fixedPlansThroughTheProductionTurnLoop() =
        runBlocking {
            assumeTrue(
                androidx.test.platform.app.InstrumentationRegistry
                    .getArguments()
                    .getString("helix.eval") == "true",
            )
            val config = Json.parseToJsonElement(File(directory, "config.json").readText()).jsonObject
            val corpus = File(directory, "fixed-evals.tsv").readBytes()
            require(hash(corpus) == "f27bf8b51e61be248a6e642c22cefc3e5045d0d35e37518377eb9b8cdf85e795")
            val rows = corpus.toString(Charsets.UTF_8).lines().filter { it.startsWith("plan-") }
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
                "HXA-100 Plan",
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
        val reports =
            listOf("alpha.md", "beta.md", "gamma.md", "rfc8259.txt").map {
                File(app.filesDir, "workspaces/app/eval-plan/$it")
            }
        val saved = reports.associateWith { it.takeIf(File::exists)?.readBytes() }
        reports.forEach { file ->
            file.parentFile!!.mkdirs()
            file.writeText(
                if (file.name == "rfc8259.txt") {
                    File(directory, "rfc8259.txt").readText()
                } else {
                    "Synthetic report ${file.name}: all checks passed.\n"
                },
            )
        }
        val session = container.chatService.createSession("fixed-${cells[0]}", provider, model)
        val context = planFixtureContext(cells[0])
        container.storage.messages.append("$session-context", session, null, "USER", "TEXT", context)
        container.storage.messages.append(
            "$session-ack",
            session,
            null,
            "ASSISTANT",
            "TEXT",
            "Planning context received.",
        )
        try {
            container.chatService.openSession(session)
            container.chatService.setMode(AgentMode.valueOf(cells[2]))
            container.chatService.setTurnBudgets(TurnBudgets(8, 6, 131072, 4096, 131072))
            container.chatService.send(cells[4])
            awaitTurn(session, cells[0])
            saveResult(cells, session, context)
        } finally {
            container.chatService.stop()
            container.chatService.closeSession()
            saved.forEach { (file, bytes) -> if (bytes == null) file.delete() else file.writeBytes(bytes) }
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
                calls.forEach { resolveApproval(it) }
                if (TurnState.valueOf(turn.state).isTerminal) return
            }
            Thread.sleep(100)
        }
        error("fixed file case timed out: $caseId")
    }

    @Suppress("ReturnCount") // Plan must never escalate an unexpected approval request
    private fun resolveApproval(call: ToolCallEntity) {
        if (call.state != "AWAITING_APPROVAL") return
        val approval = container.storage.approvals.byToolCall(call.callId) ?: return
        if (!resolvedApprovals.add(approval.id)) return
        container.chatService.denyApproval(approval.id)
    }

    private fun verifyCase(
        id: String,
        calls: List<ToolCallEntity>,
        text: String,
    ): Boolean {
        val readsOnly =
            calls.all {
                container.toolPipeline.resolveLatest(it.name)?.operationClass ==
                    com.helix.core.model.ToolOperationClass.READ_ONLY
            }
        return readsOnly &&
            when (id) {
                "plan-001" -> {
                    listOf("alpha.md", "beta.md", "gamma.md").all { text.contains(it) }
                }

                "plan-002" -> {
                    calls.any { it.name == "read" && it.state == "COMPLETED" } &&
                        text.contains("https://www.rfc-editor.org/rfc/rfc8259.txt")
                }

                "plan-003" -> {
                    text.contains("build", true) && text.contains("reference", true)
                }

                else -> {
                    false
                }
            }
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
        val passed = turn.state == "COMPLETED" && verifyCase(cells[0], calls, text)
        val config = Json.parseToJsonElement(File(directory, "config.json").readText()).jsonObject
        val result =
            buildJsonObject {
                put("id", cells[0])
                put("protocol", cells[3])
                put("provider", config.getValue("provider"))
                if (cells[0] == "plan-002") put("publicSourceSha256", config.getValue("publicSourceSha256"))
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
                    put("status", result?.status)
                    put("summary", result?.summary)
                    put("content", result?.let { container.storage.toolResults.readContent(it) })
                }
            },
        )

    private fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

private fun planFixtureContext(id: String): String =
    when (id) {
        "plan-001" -> {
            "The selected workspace is scope:app:. The three reports are under eval-plan/: " +
                "alpha.md, beta.md and gamma.md. Plan their read-only review."
        }

        "plan-002" -> {
            "The public JSON specification RFC 8259 was downloaded before this Plan turn from " +
                "https://www.rfc-editor.org/rfc/rfc8259.txt. " +
                "Read the saved copy at scope:app:eval-plan/rfc8259.txt and cite the source URL. " +
                "This mode forbids network calls."
        }

        "plan-003" -> {
            "The build error is an unresolved Kotlin reference. No source project is attached. " +
                "Propose diagnosis steps from this information without executing changes."
        }

        else -> {
            error("unknown Plan case")
        }
    }
