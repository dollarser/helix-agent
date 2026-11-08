package com.helix.app.proot

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.AppContainer
import com.helix.app.HelixApplication
import com.helix.app.account.BuiltInAccountWire
import com.helix.app.application.tools.ToolFailureContent
import com.helix.app.provider.BuiltInModel
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.DispatchOutcomeCode
import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.ProviderId
import com.helix.core.model.SessionId
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.model.ToolDispatchOutcome
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.policy.DiagnosticRedaction
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.repository.ToolAvailabilityRepository
import com.helix.provider.api.wire.WireClient
import kotlinx.coroutines.Dispatchers
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real failed execution and ordinary user goals; neither model tools nor their order are prescribed. */
class NaturalFailureLogTaskDeviceTest {
    @Test fun modelDiagnosesOriginalFailureThroughProductionRequestContext(): Unit = runBlocking { runTask(false) }

    @Test fun modelRepairsDocumentFromOriginalStderrAndCompletesUserGoal(): Unit = runBlocking { runTask(true) }

    // Independent owned cases share preparation, evidence and unconditional cleanup.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun runTask(repair: Boolean) {
        val flag = InstrumentationRegistry.getArguments().getString("naturalDiscovery")
        assumeTrue("Explicit live authorization absent", flag != null)
        require(flag == "true")
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        require(app.packageName == "com.helix.agent.developer.asrvalidation")
        val c = app.awaitAppContainer()
        check(
            app.account.signedIn.value,
        ) { "Owned validation account must already be signed in; no login is performed" }
        check(c.storage.sessions.find("session") == null) { "Refuse to replace an existing fixture session" }
        check(c.memory?.list(com.helix.core.workspace.memory.MemoryScope.Global).isNullOrEmpty()) {
            "The isolated package must contain no global user Memory"
        }
        val label = if (repair) "repair" else "diagnosis"
        val directory = File(app.cacheDir, "natural-failure-tasks/$label-${UUID.randomUUID()}")
        check(directory.mkdirs())
        check(!c.chatService.screen.value.isDraft) { "Refuse to replace a user's open draft" }
        val previousSession = c.chatService.screen.value.openSessionId
        val factoryField =
            c.providerService.javaClass
                .getDeclaredField("factory")
                .apply { isAccessible = true }
        val factory = factoryField.get(c.providerService)
        val wireField = factory.javaClass.getDeclaredField("builtInWire").apply { isAccessible = true }
        val originalWire = wireField.get(factory) as WireClient
        // Preserve the account's invocation snapshot after restoration without inspecting credentials.
        val delegate = (originalWire as? BuiltInAccountWire)?.newInvocation() ?: originalWire
        val wire =
            LogComparisonWire(delegate, directory) { tools ->
                c.toolPipeline.mcpDiscovery
                    .catalog("session")
                    .modelContext(tools)
            }
        val expected = "SYNTH_REPAIRED_${UUID.randomUUID().toString().replace("-", "") }"
        val descriptors =
            c.toolPipeline.registry
                .all()
                .distinctBy { it.origin.canonicalOf() to it.name }
        val availability =
            NaturalFixtureAvailability(
                c.storage.toolAvailability,
                descriptors.map { it.origin.canonicalOf() to it.name.value },
                "session",
            )
        var initializedFixture: InPlaceLogCloseoutDeviceTest.Fixture? = null
        var active: String? = null
        var evidence: JsonObject? = null
        try {
            val fixture = InPlaceLogCloseoutDeviceTest.Fixture(c.storage).also { initializedFixture = it }
            val workspace = admitWorkspace(c)
            val target = File(workspace, "document.txt")
            if (repair) target.writeText("label=stale\nretain=SYNTH_KEEP_529\n")
            val command =
                if (repair) {
                    "actual=\$(sed -n 's/^label=//p' document.txt); " +
                        "if [ \"\$actual\" != '$expected' ]; then " +
                        "printf '%s\\n' '$MARKER: document.txt label mismatch; expected=$expected' >&2; exit 7; fi"
                } else {
                    "printf $MARKER >&2; exit 7"
                }
            prepareFailure(c, fixture, command, workspace)
            val originalBinding = ProotJobBindingStore(c.storage).resolve("call")
            configureSession(c, repair)
            val allowed = naturalFailureAllowedTools(repair)
            check(
                descriptors.map { it.name.value }.containsAll(allowed),
            ) { "Required fixture tools are not registered" }
            availability.configure(allowed)
            val available = requireNotNull(c.toolPipeline.disabledToolFilter)
            descriptors.filter { it.name.value in allowed }.forEach {
                check(available("session", it)) { "Required fixture tool is unavailable: ${it.name.value}" }
            }
            val prompt =
                if (repair) {
                    val root =
                        requireNotNull(
                            c.storage.sessions
                                .resolve("session")
                                .directoryRef,
                        ).removeSuffix(".")
                    "上一次检查失败。请依据它保存的错误证据修正 ${root}document.txt 的 label 字段，" +
                        "保留文件其余内容，并确认目标值已保存。说明实际错误标记与退出码。" +
                        "不要重新执行命令，不访问网络，只操作这份合成资料。原始日志引用为 turn/call。"
                } else {
                    LOG_COMPARISON_PROMPT
                }
            wireField.set(factory, wire)
            active =
                c.agentRuntime
                    .submit(
                        SubmitTurnCommand(
                            SessionId("session"),
                            ProviderId(BuiltInModel.PROVIDER_ID),
                            AgentMode.ACT,
                            prompt,
                            TurnBudgets(4096),
                            clientRequestId = UUID.randomUUID().toString(),
                            directUserRequest = true,
                        ),
                    ).value
            val id = requireNotNull(active)
            // A harness hang guard, not a per-request quota or a retry-until-success loop.
            var timedOut = false
            val deadline = android.os.SystemClock.elapsedRealtime() + 180_000
            while (!TurnState
                    .valueOf(
                        c.storage.turns
                            .resolve(id)
                            .state,
                    ).isTerminal
            ) {
                if (android.os.SystemClock.elapsedRealtime() >= deadline) {
                    timedOut = true
                    stopAndAwait(c, id)
                    break
                }
                delay(100)
            }
            evidence =
                observation(
                    c,
                    fixture,
                    id,
                    directory,
                    expected.takeIf { repair },
                    originalBinding,
                    target,
                ).let { facts ->
                    JsonObject(
                        facts +
                            buildJsonObject {
                                put("case", label)
                                put("prompt", prompt)
                                put("timedOut", timedOut)
                                put("wireRequests", wire.calls)
                                put("httpStatuses", JsonArray(wire.statuses.map(::JsonPrimitive)))
                                put("taskPassed", !timedOut && facts.getValue("taskPassed").jsonPrimitive.boolean)
                            },
                    )
                }
            save(File(directory, "result.json"), requireNotNull(evidence))
            assertTrue(
                "Inspect result.json: a completed Turn alone is not task success",
                evidence.getValue("taskPassed").jsonPrimitive.boolean,
            )
        } finally {
            withContext(NonCancellable) {
                var settled = false
                var closed = false
                var availabilityRestored = false
                try {
                    active?.let { stopAndAwait(c, it) }
                    settled = true
                } finally {
                    try {
                        wireField.set(factory, originalWire)
                    } finally {
                        try {
                            // A cancellation timeout must not delete a still-owned running task's state.
                            if (settled) {
                                try {
                                    initializedFixture?.let { closeFixture(c, it, previousSession) }
                                    closed = initializedFixture != null
                                } finally {
                                    availability.restore()
                                    availabilityRestored = true
                                }
                            }
                        } finally {
                            save(
                                File(directory, "lifecycle.json"),
                                buildJsonObject {
                                    put("observationWritten", evidence != null)
                                    put("transportRestored", wireField.get(factory) === originalWire)
                                    put("turnSettled", settled)
                                    put("fixtureClosed", closed)
                                    put("toolAvailabilityRestored", availabilityRestored)
                                    put("loginPerformed", false)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun closeFixture(
        c: AppContainer,
        fixture: InPlaceLogCloseoutDeviceTest.Fixture,
        previous: String?,
    ) {
        withContext(Dispatchers.Main.immediate) {
            if (previous == null) c.chatService.closeSession() else c.chatService.openSession(previous)
        }
        val binding = c.storage.workspaces.binding("session")
        val resource = binding?.let { requireNotNull(c.storage.workspaces.find(it.workspaceId)) }
        val directory =
            resource?.let {
                check(it.ownership == "MANAGED" && it.ownerSessionId == "session")
                c.storage.workspaces
                    .managedDirectory(it.id)
                    .toFile()
                    .canonicalFile
            }
        fixture.close()
        if (resource != null && directory != null) {
            check(c.storage.workspaces.references(resource.id) == 0)
            check(directory.deleteRecursively())
            c.storage.workspaces.markUnavailable(resource.id)
        }
    }

    private suspend fun admitWorkspace(c: AppContainer): File {
        withContext(Dispatchers.Main.immediate) { c.chatService.openSession("session") }
        return withTimeout(15_000) {
            while (c.storage.workspaces.binding("session") == null) delay(50)
            val binding = requireNotNull(c.storage.workspaces.binding("session"))
            val resource = requireNotNull(c.storage.workspaces.find(binding.workspaceId))
            check(resource.ownership == "MANAGED" && resource.ownerSessionId == "session")
            check(binding.relativePath.isEmpty())
            c.storage.workspaces
                .managedDirectory(resource.id)
                .toFile()
                .canonicalFile
        }
    }

    private fun prepareFailure(
        c: AppContainer,
        fixture: InPlaceLogCloseoutDeviceTest.Fixture,
        command: String,
        workspace: File,
    ) {
        val record = fixture.run(command, workspaceOverride = workspace)
        assertEquals(7, record.exitCode)
        fixture.persist(record)
        val failure =
            terminalFailure(record, "Original command failed; inspect stderr", "JOB_FAILED", requiresReview = false)
        c.storage.toolResults.append(
            "natural-original-result",
            "row",
            "FAILED",
            "Original failed",
            ToolFailureContent.encode(
                ToolDispatchOutcome.ExecutionFailed(DispatchOutcomeCode.TOOL_FAILED, failure.detail),
            ),
        )
        val original = c.storage.turns.resolve("turn")
        val building = c.storage.turns.updateState(original, TurnState.BUILDING_CONTEXT, 0, null, null)
        c.storage.turns.updateState(building, TurnState.FAILED, 0, System.currentTimeMillis(), "TOOL_FAILED")
        c.storage.sessions.bindProvider("session", BuiltInModel.PROVIDER_ID, BuiltInModel.MODEL_ID)
    }

    private fun configureSession(
        c: AppContainer,
        repair: Boolean,
    ) {
        val rules =
            OperationEffect.values().associateWith { effect ->
                when (effect) {
                    OperationEffect.FILE_READ_WORKSPACE -> OperationRule.ALLOW
                    OperationEffect.FILE_MUTATION_WORKSPACE -> if (repair) OperationRule.ALLOW else OperationRule.DENY
                    else -> OperationRule.DENY
                }
            }
        c.storage.sessionPermissionConfigs.setForSession(
            "session",
            SessionPermissionConfig.custom(rules),
            System.currentTimeMillis(),
        )
    }

    // Keep every independent acceptance predicate beside the evidence persisted even when the task fails.
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun observation(
        c: AppContainer,
        fixture: InPlaceLogCloseoutDeviceTest.Fixture,
        id: String,
        directory: File,
        expected: String?,
        originalBinding: JsonObject,
        target: File,
    ): JsonObject {
        val calls = c.storage.toolCalls.listByTurn(id)
        val answer =
            c.storage.messages
                .listBySession("session")
                .filter { it.turnId == id && it.role == "ASSISTANT" && it.kind == "TEXT" }
                .joinToString("\n") {
                    c.storage.messages
                        .readContent(it)
                        .orEmpty()
                }
        val requests =
            directory
                .listFiles()
                .orEmpty()
                .filter { Regex("request-\\d+\\.json").matches(it.name) }
                .sortedBy {
                    it.name
                        .removePrefix("request-")
                        .removeSuffix(".json")
                        .toInt()
                }.map {
                    Json
                        .parseToJsonElement(it.readText())
                        .jsonObject
                        .getValue("sent")
                        .jsonObject
                }
        val originalStderr = fixture.log("saved", "stderr")
        val results =
            calls.associate { call ->
                call.id to
                    c.storage.toolResults
                        .byToolCall(call.id)
                        ?.let(c.storage.toolResults::readContent)
            }
        val stderrCalls =
            calls.filter { call ->
                val result =
                    results[call.id]?.let {
                        runCatching {
                            Json
                                .parseToJsonElement(
                                    it,
                                ).jsonObject
                        }.getOrNull()
                    }
                call.state == "COMPLETED" && result != null && savedStderr(result, originalStderr)
            }
        // Only owned, persisted assistant steps can bind a local execution to its provider wire ID.
        val persistedCalls =
            JsonArray(
                c.storage.messages
                    .listBySession("session")
                    .filter { it.turnId == id && it.role == "ASSISTANT" && it.kind == "TOOL_CALLS" }
                    .flatMap {
                        Json.parseToJsonElement(
                            requireNotNull(c.storage.messages.readContent(it)),
                        ) as JsonArray
                    },
            )
        val consumed =
            stderrCalls.any { call ->
                requests.any { sentToolContent(it, persistedCalls, id, call.callId, originalStderr) }
            }
        val initialUncontaminated =
            requests.firstOrNull()?.toString()?.let {
                !it.contains(MARKER) && (expected == null || !it.contains(expected))
            } == true
        val audits =
            calls.associate { call ->
                call.id to
                    c.storage.auditEvents
                        .listByCorrelation(call.id)
                        .filter { it.type == "tool_dispatch" }
            }
        val unexpected =
            calls.filter { call ->
                c.storage.executions.byToolCall(call.id) != null ||
                    audits.getValue(call.id).any { event ->
                        val payload = Json.parseToJsonElement(event.redactedPayload).jsonObject
                        payload["operationClass"]?.jsonPrimitive?.content == "CODE_EXECUTION" &&
                            payload["executionStartedAt"]?.let { it != JsonNull } == true
                    }
            }
        val unchanged =
            ProotJobBindingStore(c.storage).resolve("call") == originalBinding &&
                c.storage.toolCalls
                    .resolve("row")
                    .state == "FAILED" && fixture.submissions == 1
        val actualBytes = target.takeIf { it.isFile }?.readBytes()
        val actual = actualBytes?.toString(Charsets.UTF_8)
        val repaired = expected == null || repairedDocument(actual, expected)
        val actualHash = actualBytes?.let(FileContentStore::sha256Hex)
        val targetReference =
            c.storage.sessions
                .resolve("session")
                .directoryRef
                ?.removeSuffix(".") + "document.txt"
        val documentObserved =
            expected == null ||
                calls.any { call ->
                    val result =
                        results[call.id]?.let {
                            runCatching {
                                Json
                                    .parseToJsonElement(
                                        it,
                                    ).jsonObject
                            }.getOrNull()
                        }
                    call.state == "COMPLETED" && actualHash != null &&
                        result?.get("path")?.jsonPrimitive?.content == targetReference &&
                        result?.get("sha256")?.jsonPrimitive?.content == actualHash &&
                        requests.any { sentToolContent(it, persistedCalls, id, call.callId, actualHash) }
                }
        val completionReported = expected == null || answer.contains(expected)
        val exitRead = exitSeven(answer)
        val state =
            c.storage.turns
                .resolve(id)
                .state
        val pass =
            state == "COMPLETED" && consumed && answer.contains(MARKER) && exitRead && unchanged &&
                unexpected.isEmpty() && initialUncontaminated && repaired && documentObserved && completionReported
        return buildJsonObject {
            put("taskPassed", pass)
            put("state", state)
            put("answer", answer)
            put("initialRequestContainsNoAnswer", initialUncontaminated)
            put("actualSavedStderr", originalStderr)
            put("stderrSha256", FileContentStore.sha256Hex(originalStderr.toByteArray()))
            put("savedStderrRead", stderrCalls.isNotEmpty())
            put("stderrConsumedInLaterRequest", consumed)
            put("answerHasExplicitExitSeven", exitRead)
            put("originalSubmissions", fixture.submissions)
            put("originalExecutionUnchanged", unchanged)
            put("originalJobId", fixture.spec.jobId)
            put("originalExecutionId", fixture.spec.executionId)
            put("unexpectedCommandStarts", unexpected.size)
            put("documentGoalRequired", expected != null)
            put("documentGoalVerified", repaired)
            put("documentReceiptConsumedInLaterRequest", documentObserved)
            put("documentCompletionReported", completionReported)
            put("documentContent", actual)
            put("documentSha256", actualHash)
            put("expectedLabel", expected)
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
                "calls",
                JsonArray(
                    calls.map { call ->
                        buildJsonObject {
                            put("name", call.name)
                            put("callId", call.callId)
                            put("arguments", call.argsJson)
                            put("state", call.state)
                            put("result", results[call.id])
                            put(
                                "dispatchAudit",
                                JsonArray(
                                    audits.getValue(call.id).map {
                                        Json.parseToJsonElement(it.redactedPayload)
                                    },
                                ),
                            )
                        }
                    },
                ),
            )
        }
    }

    private suspend fun stopAndAwait(
        c: AppContainer,
        id: String,
    ) {
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

    private fun save(
        file: File,
        value: JsonObject,
    ) = file.writeText(DiagnosticRedaction.redact(value.toString(), emptySet()))

    companion object {
        internal const val MARKER = "SYNTH_NATURAL_ERROR_731"

        internal fun savedStderr(
            result: JsonObject,
            actual: String,
        ): Boolean =
            result["source"]?.jsonPrimitive?.content == "saved" &&
                result["stream"]?.jsonPrimitive?.content == "stderr" &&
                result["logRef"]?.jsonPrimitive?.content == "turn/call" &&
                result["exitCode"]?.jsonPrimitive?.intOrNull == 7 &&
                result["content"]?.jsonPrimitive?.content == actual && actual.contains(MARKER)

        internal fun sentToolContent(
            request: JsonObject,
            persistedCalls: JsonArray,
            turnId: String,
            localId: String,
            content: String,
        ): Boolean {
            val binding =
                persistedCalls
                    .map { it.jsonObject }
                    .singleOrNull { it["localId"]?.jsonPrimitive?.content == localId } ?: return false
            val wireId = binding["id"]?.jsonPrimitive?.content
            check(!wireId.isNullOrEmpty()) { "Persisted assistant call has no wire identity" }
            val messages = (request["messages"] as? JsonArray).orEmpty().map { it.jsonObject }
            val assistantIndex =
                messages.indexOfFirst { message ->
                    message["role"]?.jsonPrimitive?.content == "assistant" &&
                        (message["tool_calls"] as? JsonArray).orEmpty().any { entry ->
                            val call = entry.jsonObject
                            val function = call["function"] as? JsonObject
                            call["id"]?.jsonPrimitive?.content == wireId &&
                                function?.get("name") == binding["name"]
                        }
                }
            return assistantIndex >= 0 &&
                messages.drop(assistantIndex + 1).any { message ->
                    val body = (message["content"] as? JsonPrimitive)?.content.orEmpty()
                    val delivery =
                        runCatching {
                            Json.parseToJsonElement(body.substringAfterLast("\nDelivery facts: ", "")) as? JsonObject
                        }.getOrNull()
                    // Arguments and result paths may be projected relative to the request workspace.
                    // The persisted wire mapping and exact delivery callRef retain execution identity.
                    message["role"]?.jsonPrimitive?.content == "tool" &&
                        message["tool_call_id"]?.jsonPrimitive?.content == wireId &&
                        delivery?.get("callRef")?.jsonPrimitive?.content == "$turnId/$localId" &&
                        body.substringBeforeLast("\nDelivery facts: ").contains(content.trimEnd())
                }
        }

        internal fun exitSeven(answer: String): Boolean =
            Regex("(?:退出码|exit(?:\\s+code|Code))\\D{0,16}7(?!\\d)", RegexOption.IGNORE_CASE).containsMatchIn(answer)

        internal fun repairedDocument(
            actual: String?,
            expected: String,
        ): Boolean = actual == "label=$expected\nretain=SYNTH_KEEP_529\n"
    }
}

/** Availability has no session foreign key, so each owned case scopes and restores its own rows. */
internal class NaturalFixtureAvailability(
    private val repository: ToolAvailabilityRepository,
    private val identities: List<Pair<String, String>>,
    private val session: String,
) {
    private val snapshot =
        identities.associateWith { (source, name) ->
            repository
                .byTool(source, name)
                .singleOrNull {
                    it.scopeKind == ToolAvailabilityScope.SESSION.name && it.scopeRef == session
                }?.let { ToolAvailabilityState.valueOf(it.state) }
        }
    private var configured = false

    fun configure(allowed: Set<String>) {
        configured = true
        identities.forEach { (source, name) ->
            repository.set(
                source,
                name,
                ToolAvailabilityScope.SESSION,
                session,
                if (name in allowed) ToolAvailabilityState.ENABLED else ToolAvailabilityState.DISABLED,
                System.currentTimeMillis(),
            )
        }
    }

    fun restore() {
        if (!configured) return
        snapshot.forEach { (identity, state) ->
            val (source, name) = identity
            if (state == null) {
                if (repository.byTool(source, name).any {
                        it.scopeKind == ToolAvailabilityScope.SESSION.name && it.scopeRef == session
                    }
                ) {
                    repository.remove(source, name, ToolAvailabilityScope.SESSION, session)
                }
            } else {
                repository.set(source, name, ToolAvailabilityScope.SESSION, session, state, System.currentTimeMillis())
            }
        }
        configured = false
    }
}

internal fun naturalFailureAllowedTools(repair: Boolean): Set<String> =
    setOf("tools.search", "tools.read_result", ProotLogTool.NAME) +
        if (repair) setOf("read", "edit", "write", "files.stat", "files.list") else emptySet()
