package com.helix.app.eval

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.app.application.diagnostics.DiagnosticRedaction
import com.helix.app.application.tools.ToolFailureContent
import com.helix.app.proot.InPlaceLogCloseoutDeviceTest
import com.helix.app.proot.LOG_COMPARISON_PROMPT
import com.helix.app.proot.ProotLogTool
import com.helix.app.proot.diagnosticJson
import com.helix.app.proot.minimalLogContext
import com.helix.app.proot.modelContext
import com.helix.app.proot.terminalFailure
import com.helix.app.provider.BuiltInModel
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.ProviderId
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.SessionId
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.workspace.memory.MemoryMarkdown
import com.helix.core.workspace.memory.MemoryScope
import com.helix.core.workspace.memory.ProjectMemoryScopeKey
import com.helix.provider.api.wire.WireClient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Experimental wire ablation, not a production policy replacement. Only synthetic, isolated scopes. */
@Suppress("TooManyFunctions")
class MemoryLogEffectDeviceTest {
    private lateinit var c: AppContainer
    private lateinit var app: HelixApplication
    private lateinit var output: File
    private lateinit var wireField: java.lang.reflect.Field
    private lateinit var factory: Any
    private lateinit var original: WireClient
    private val budget = EffectWireBudget()
    private val results = mutableListOf<JsonObject>()

    @Test
    // Record finite experiment failures while always restoring transport and account ownership.
    @Suppress("LongMethod", "TooGenericExceptionCaught", "CyclomaticComplexMethod")
    fun fixedPairsUseNaturalToolSelectionAndRecordNegativeResults(): Unit =
        runBlocking {
            val flag = InstrumentationRegistry.getArguments().getString("effectExperiment")
            assumeTrue("Explicit model experiment absent", flag != null)
            require(flag == "true")
            app = ApplicationProvider.getApplicationContext()
            require(app.packageName.startsWith("com.helix.validation.memorylog"))
            var wasEnabled: Boolean? = null
            var batchFailure: String? = null
            var batchCompleted = false
            try {
                c = app.awaitAppContainer()
                check(app.account.signedIn.value)
                output = File(app.cacheDir, "memory-log-effect/results").apply { check(mkdirs()) }
                val planFile = File(app.cacheDir, "memory-log-effect/plan.json")
                val plan = Json.parseToJsonElement(planFile.readText()).jsonObject
                require(plan.getValue("version").jsonPrimitive.int == 1)
                require(plan.getValue("maxCases").jsonPrimitive.int == 10)
                require(plan.getValue("model").jsonPrimitive.content == BuiltInModel.MODEL_ID)
                val frozenBudgets =
                    mapOf(
                        "maxWireRequestsPerCase" to 4,
                        "maxWireRequestsTotal" to 40,
                        "maxBodyBytesTotal" to 2_097_152,
                        "caseTimeoutMs" to 90_000,
                        "totalTimeoutMs" to 900_000,
                        "maxOutputTokens" to 1024,
                    )
                frozenBudgets.forEach { (key, value) ->
                    require(plan.getValue(key).jsonPrimitive.int == value) { "Frozen budget changed: $key" }
                }
                val cases = plan.getValue("memoryCases") as JsonArray
                require(cases.size == 4) { "Exactly four paired Memory tasks are frozen" }
                val field =
                    c.providerService.javaClass
                        .getDeclaredField("factory")
                        .apply { isAccessible = true }
                factory = field.get(c.providerService)
                wireField = factory.javaClass.getDeclaredField("builtInWire").apply { isAccessible = true }
                original = wireField.get(factory) as WireClient
                val memory = requireNotNull(c.memory)
                check(memory.list(MemoryScope.Global).isEmpty()) {
                    "Fresh experiment package must contain no user Memory"
                }
                wasEnabled = memory.enabled
                withTimeout(900_000) {
                    memory.configure("enabled", true)
                    for ((ordinal, item) in cases.withIndex()) {
                        memoryPair(item.jsonObject, ordinal)
                        if (serviceBlocked()) break
                    }
                    memory.configure("enabled", false)
                    if (!serviceBlocked()) {
                        for (minimal in listOf(false, true)) {
                            logCase(plan.getValue("log").jsonObject, minimal)
                            if (serviceBlocked()) break
                        }
                    }
                }
                batchCompleted = true
            } catch (failure: Exception) {
                batchFailure = failure.javaClass.simpleName
                throw failure
            } finally {
                withContext(NonCancellable) {
                    var restoration = "not-needed"
                    try {
                        val wireReady =
                            ::wireField.isInitialized && ::factory.isInitialized && ::original.isInitialized
                        if (wireReady) {
                            wireField.set(factory, original)
                        }
                        wasEnabled?.let { requireNotNull(c.memory).configure("enabled", it) }
                        restoration = "restored-initialized-state"
                    } catch (failure: Exception) {
                        restoration = "failed:${failure.javaClass.simpleName}"
                    }
                    var logout = "not-confirmed"
                    try {
                        withTimeout(30_000) { app.account.logout() }
                        check(!app.account.signedIn.value)
                        logout = "local-session-cleared-and-logout-returned"
                    } catch (failure: Exception) {
                        logout = "failed:${failure.javaClass.simpleName}"
                    }
                    val destination =
                        if (::output.isInitialized) {
                            output
                        } else {
                            File(app.cacheDir, "memory-log-effect/preparation-failure-${UUID.randomUUID()}")
                                .apply { check(mkdirs()) }
                        }
                    save(
                        File(destination, "summary.json"),
                        buildJsonObject {
                            put("sourceBaseline", "stage1-ac6d8012-plus-recorded-goal-boundary-fix")
                            put("plannedTasks", 10)
                            put("executedTasks", results.size)
                            put("tasks", JsonArray(results))
                            put("wireRequests", budget.requests)
                            put("wireBodyBytes", budget.bytes)
                            put(
                                "batchFailure",
                                batchFailure ?: if (!batchCompleted) "UNHANDLED_FAILURE" else null,
                            )
                            put("restoration", restoration)
                            put("logout", logout)
                            put("candidateIsDiagnosticWireProjection", true)
                        },
                    )
                    check(!restoration.startsWith("failed:")) { "Experiment state restoration failed" }
                    check(logout == "local-session-cleared-and-logout-returned") {
                        "Experiment logout failed"
                    }
                }
            }
        }

    // One pair owns its synthetic project data and unconditional cleanup.
    @Suppress("LongMethod")
    private suspend fun memoryPair(
        spec: JsonObject,
        ordinal: Int,
    ) {
        val projects = requireNotNull(c.projects)
        val memory = requireNotNull(c.memory)
        val id = spec.text("id")
        val project = projects.save(null, "Synthetic $id", "", REQUIRED, null)
        val other = projects.save(null, "Synthetic other", "", REQUIRED, null)
        val scope = MemoryScope.Project(ProjectMemoryScopeKey(project))
        val body =
            spec.text("summaryPrefix").repeat(spec.getValue("repeatPrefix").jsonPrimitive.int) +
                spec.text("summaryTail")
        memory.save(
            scope,
            "memory_summary.md",
            MemoryMarkdown.encode("project", "Synthetic project summary", body, 1000),
            "new",
        )
        for (topic in spec.getValue("topics") as JsonArray) {
            val row = topic.jsonObject
            memory.save(
                scope,
                row.text("path"),
                MemoryMarkdown.encode("project", row.text("source"), row.text("body"), 1000),
                "new",
            )
        }
        if (id == "other-project") {
            memory.save(
                MemoryScope.Project(ProjectMemoryScopeKey(other)),
                "release.md",
                MemoryMarkdown.encode("project", "Other project release", spec.text("forbidden"), 1000),
                "new",
            )
        }
        try {
            val order = if (ordinal % 2 == 0) listOf(false, true) else listOf(true, false)
            for (indexed in order) {
                val session = projects.newSession(project, BuiltInModel.PROVIDER_ID, BuiltInModel.MODEL_ID)
                val denied = spec["denyMemory"]?.jsonPrimitive?.content == "true"
                permissions(session, denied)
                val indexes = if (denied) emptyMap() else mapOf("project" to index(scope))
                try {
                    runCase(
                        session,
                        "$id-${if (indexed) "index" else "baseline"}",
                        spec.text("question"),
                        if (indexed) ({ request -> memoryIndexProjection(request, indexes) }) else ({ it }),
                        spec["expected"]?.jsonPrimitive?.content,
                        spec["forbidden"]?.jsonPrimitive?.content,
                        required = true,
                        log = false,
                        memoryAllowed = !denied,
                    )
                } finally {
                    c.storage.deleteSessionPermanently(session)
                }
                if (serviceBlocked()) break
            }
        } finally {
            for (entry in memory.list(scope)) memory.delete(scope, entry.path, entry.hash)
            val foreign = MemoryScope.Project(ProjectMemoryScopeKey(other))
            for (entry in memory.list(foreign)) memory.delete(foreign, entry.path, entry.hash)
            projects.delete(requireNotNull(c.storage.projects.find(project)))
            projects.delete(requireNotNull(c.storage.projects.find(other)))
        }
    }

    private fun index(scope: MemoryScope): String =
        requireNotNull(c.memory).list(scope).joinToString("\n") { entry ->
            val source =
                entry.markdown
                    .lineSequence()
                    .firstOrNull { it.startsWith("source:") }
                    .orEmpty()
            "${entry.path} type=${entry.type} hash=${entry.hash} $source"
        }

    private fun permissions(
        session: String,
        denied: Boolean = false,
    ) {
        val rules = SessionPermissionConfig.copyPreset(SessionPermissionMode.READ_ONLY).toMutableMap()
        rules[OperationEffect.FILE_READ_EXTERNAL] = if (denied) OperationRule.DENY else OperationRule.ALLOW
        c.storage.sessionPermissionConfigs.setForSession(session, SessionPermissionConfig.custom(rules), 1000)
    }

    // The original execution and saved log evidence share one finally-owned fixture.
    @Suppress("LongMethod")
    private suspend fun logCase(
        spec: JsonObject,
        minimal: Boolean,
    ) {
        require(spec.text("question") == LOG_COMPARISON_PROMPT) { "Frozen log question changed" }
        val fixture = InPlaceLogCloseoutDeviceTest.Fixture(c.storage)
        try {
            resetExposure("session")
            val record = fixture.run("printf SYNTH_NATURAL_ERROR_731 >&2; exit 7")
            fixture.persist(record)
            val failed =
                terminalFailure(
                    record,
                    "Original command failed; inspect stderr",
                    "JOB_FAILED",
                    requiresReview = false,
                )
            c.storage.toolResults.append(
                "effect-original-result",
                "row",
                "FAILED",
                "Original failed",
                ToolFailureContent.encode(
                    ToolDispatchOutcome.ExecutionFailed(DispatchOutcomeCode.TOOL_FAILED, failed.detail),
                ),
            )
            val old = c.storage.turns.resolve("turn")
            val building = c.storage.turns.updateState(old, TurnState.BUILDING_CONTEXT, 0, null, null)
            c.storage.turns.updateState(
                building,
                TurnState.FAILED,
                0,
                System.currentTimeMillis(),
                "TOOL_FAILED",
            )
            c.storage.sessions.bindProvider("session", BuiltInModel.PROVIDER_ID, BuiltInModel.MODEL_ID)
            permissions("session")
            val label = if (minimal) "log-minimal-diagnostic" else "log-full-production-plan"
            runCase(
                "session",
                label,
                spec.text("question"),
                { request ->
                    if (minimal) {
                        minimalLogContext(
                            request,
                            c.toolPipeline.mcpDiscovery
                                .catalog("session")
                                .modelContext(callableTools(request)),
                        )
                    } else {
                        request
                    }
                },
                spec.text("expected"),
                null,
                required = false,
                log = true,
            )
            assertEquals(1, fixture.submissions)
            save(
                File(output, "$label/original-operation.json"),
                buildJsonObject {
                    put("jobId", fixture.spec.jobId)
                    put("executionId", fixture.spec.executionId)
                    put("submissions", fixture.submissions)
                    put("exitCode", record.exitCode)
                    put(
                        "stderrSha256",
                        com.helix.core.storage.content.FileContentStore
                            .sha256Hex(fixture.log("local", "stderr").toByteArray()),
                    )
                },
            )
        } finally {
            fixture.close()
            resetExposure("session")
        }
    }

    // One finite observation records success/failure and finally cancels the same admitted Turn.
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
    private suspend fun runCase(
        session: String,
        label: String,
        question: String,
        transform: (JsonObject) -> JsonObject,
        expected: String?,
        forbidden: String?,
        required: Boolean,
        log: Boolean,
        memoryAllowed: Boolean = false,
    ) {
        val directory = File(output, label).apply { check(mkdirs()) }
        val wire = EffectExperimentWire(original, directory, budget, transform)
        wireField.set(factory, wire)
        val began = android.os.SystemClock.elapsedRealtime()
        var turn: String? = null
        var failure: String? = null
        try {
            turn =
                c.agentRuntime
                    .submit(
                        SubmitTurnCommand(
                            SessionId(session),
                            ProviderId(BuiltInModel.PROVIDER_ID),
                            AgentMode.PLAN,
                            question,
                            TurnBudgets(1024),
                            reasoning = ReasoningEffort.LOW,
                            clientRequestId = UUID.randomUUID().toString(),
                            directUserRequest = true,
                        ),
                    ).value
            val admitted = requireNotNull(turn)
            withTimeout(90_000) {
                while (!TurnState
                        .valueOf(
                            c.storage.turns
                                .resolve(admitted)
                                .state,
                        ).isTerminal
                ) {
                    delay(100)
                }
            }
        } catch (error: Exception) {
            failure = error.javaClass.simpleName
        } finally {
            turn?.let { id ->
                withContext(NonCancellable) {
                    c.chatService.stopTask(id)
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
                }
            }
        }
        val calls = turn?.let(c.storage.toolCalls::listByTurn).orEmpty()
        val answer =
            c.storage.messages
                .listBySession(session)
                .filter { it.turnId == turn && it.role == "ASSISTANT" && it.kind == "TEXT" }
                .joinToString("\n") {
                    c.storage.messages
                        .readContent(it)
                        .orEmpty()
                }
        val resultText =
            calls.mapNotNull { call ->
                c.storage.toolResults
                    .byToolCall(call.id)
                    ?.let(c.storage.toolResults::readContent)
            }
        val readTool = if (log) ProotLogTool.NAME else "memory.read"
        val read = calls.any { it.state == "COMPLETED" && it.name == readTool }
        val deniedAnswer =
            Regex("未知|无法|没有.*资料|无权限|不可访问|不能确定")
                .containsMatchIn(answer)
        val exitCodeRead =
            Regex(
                "(?:退出码|exit(?:\\s+code|Code))\\D{0,16}7(?!\\d)",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(answer)
        val correct =
            if (expected == null) {
                deniedAnswer && forbidden?.let { !answer.contains(it) } != false
            } else {
                read && answer.contains(expected) && (!log || exitCodeRead)
            }
        val firstRequest = File(directory, "request-0.json").takeIf { it.isFile }?.readText()
        if (required && firstRequest != null) {
            check(firstRequest.contains(REQUIRED))
            if (memoryAllowed) {
                check(firstRequest.contains("UNTRUSTED_MEMORY scope=project")) {
                    "Authorized Memory baseline absent: invalid comparison"
                }
                if (label.endsWith("-index")) {
                    val request = Json.parseToJsonElement(firstRequest).jsonObject
                    check(request.getValue("original") != request.getValue("sent")) {
                        "Index ablation did not change authorized content"
                    }
                }
            } else {
                check(!firstRequest.contains("UNTRUSTED_MEMORY scope=project"))
            }
        }
        if (forbidden != null) {
            check(firstRequest?.contains(forbidden) != true) { "Forbidden scope leaked into initial request" }
            check(resultText.none { it.contains(forbidden) }) { "Forbidden scope leaked through tool result" }
        }
        val audits =
            calls.associate { call ->
                call.id to
                    c.storage.auditEvents
                        .listByCorrelation(call.id)
                        .filter { it.type == "tool_dispatch" }
            }
        val executionRecords =
            calls.associate { call ->
                call.id to (c.storage.executions.byToolCall(call.id) != null)
            }
        val unexpectedStarts =
            calls.filter { call ->
                executionRecords.getValue(call.id) ||
                    audits.getValue(call.id).any { event ->
                        val payload = Json.parseToJsonElement(event.redactedPayload).jsonObject
                        payload["operationClass"]?.jsonPrimitive?.content == "CODE_EXECUTION" &&
                            payload["executionStartedAt"]?.let { it != JsonNull } == true
                    }
            }
        val settled = turn?.let { c.storage.turns.resolve(it) }
        val row =
            buildJsonObject {
                put("case", label)
                put(
                    "memoryReadRule",
                    c.storage.sessionPermissionConfigs
                        .forSession(session)
                        ?.ruleFor(OperationEffect.FILE_READ_EXTERNAL)
                        ?.name,
                )
                put(
                    "taskPassed",
                    correct && failure == null && settled?.state == "COMPLETED" && unexpectedStarts.isEmpty(),
                )
                put("failure", failure)
                put("state", settled?.state)
                put("errorCode", settled?.errorCode)
                put("answer", answer)
                put("unexpectedCommandStarts", unexpectedStarts.size)
                put("wireRequests", wire.calls)
                put("wireBodyBytes", wire.sentBytes)
                put("httpStatuses", JsonArray(wire.statuses.map(::JsonPrimitive)))
                put("elapsedMs", android.os.SystemClock.elapsedRealtime() - began)
                put(
                    "usage",
                    JsonArray(
                        turn?.let(c.storage.modelCalls::listByTurn).orEmpty().map { JsonPrimitive(it.usage) },
                    ),
                )
                put(
                    "catalog",
                    c.toolPipeline.mcpDiscovery
                        .catalog(session)
                        .diagnosticJson(),
                )
                put(
                    "calls",
                    JsonArray(
                        calls.map { call ->
                            buildJsonObject {
                                put("name", call.name)
                                put("arguments", call.argsJson)
                                put("state", call.state)
                                put("hasExecutionRecord", executionRecords.getValue(call.id))
                                put(
                                    "dispatchAudit",
                                    JsonArray(
                                        audits.getValue(call.id).map { event ->
                                            buildJsonObject {
                                                put("id", event.id)
                                                put("correlationId", event.correlationId)
                                                put("timestamp", event.timestamp)
                                                put("type", event.type)
                                                put("payload", Json.parseToJsonElement(event.redactedPayload))
                                            }
                                        },
                                    ),
                                )
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
            }
        results += row
        save(File(directory, "result.json"), row)
        check(unexpectedStarts.isEmpty()) { "Read-only model task started another command" }
        println("MEMORY_LOG_CASE=$label taskPassed=$correct requests=${wire.calls} failure=$failure")
        wireField.set(factory, original)
    }

    private fun serviceBlocked(): Boolean =
        results.lastOrNull()?.get("httpStatuses")?.toString()?.let {
            it.contains("401") || it.contains("403") || it.contains("402")
        } == true || !app.account.signedIn.value

    // Fresh synthetic session cache only; does not change durable permission or schemas.
    @Suppress("UNCHECKED_CAST")
    private fun resetExposure(session: String) {
        val discovery = c.toolPipeline.mcpDiscovery
        synchronized(discovery) {
            for (name in listOf("loaded", "admittedWindows")) {
                val field = discovery.javaClass.getDeclaredField(name).apply { isAccessible = true }
                (field.get(discovery) as MutableMap<String, *>).remove(session)
            }
        }
    }

    private fun callableTools(body: JsonObject): Set<String> =
        (body["tools"] as? JsonArray)
            .orEmpty()
            .mapNotNull {
                it.jsonObject["function"]
                    ?.jsonObject
                    ?.get("name")
                    ?.jsonPrimitive
                    ?.content
            }.toSet()

    private fun save(
        file: File,
        value: JsonObject,
    ) = file.writeText(DiagnosticRedaction.redact(value.toString(), emptySet()))

    private fun JsonObject.text(name: String) = getValue(name).jsonPrimitive.content

    private companion object {
        const val REQUIRED =
            "EXPERIMENT_REQUIRED_CONSTRAINT: current user instructions win; never disclose another project."
    }
}
