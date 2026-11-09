package com.helix.app.eval

import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.app.provider.ProviderDraft
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderId
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SessionId
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.TurnBudgets
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolBinding
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Adapted single-turn call-selection diagnostic, never the official BFCL agentic benchmark. */
class BfclFullAccessDeviceTest {
    @Test
    fun executeFirstResponse() =
        runBlocking {
            assumeTrue(InstrumentationRegistry.getArguments().getString("helix.bfclFullAccess") == "true")
            FixtureRun().execute()
        }

    private class FixtureRun {
        private val args = InstrumentationRegistry.getArguments()

        private fun identity(name: String): String =
            requireNotNull(args.getString(name)).also {
                require(it.matches(Regex("[A-Za-z0-9_-]{1,120}"))) { "Invalid $name" }
            }

        private val runId = identity("runId")
        private val caseId = identity("caseId")
        private val attempt = requireNotNull(args.getString("attempt")).toInt().also { require(it > 0) }
        private val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        private val c = app.appContainer
        private val destination = File(app.filesDir, "bfcl/$runId/$caseId/attempt-$attempt.json")

        init {
            check(!destination.exists()) { "Attempt evidence already exists" }
        }

        private val model = args.getString("model") ?: "Qwen3.8-27B"
        private val endpoint = args.getString("endpoint") ?: "http://10.0.2.2:30008/v1"
        private val server = "bfclfixture"
        private val syntheticApprovals = false
        private var permissionRevision: Long? = null
        private var permissionModeObserved: String? = null
        private val originalDefault = c.storage.sessionPermissionConfigs.appDefault()
        private var verifiedAvd: String? = null
        private val approvalRequests = linkedMapOf<String, String>()
        private val capturedBeforeAssistance: String? = null
        private var session: String? = null
        private var provider: String? = null
        private var turnId: String? = null
        private var status = "ERROR"
        private var failure: String? = null
        private var observedTurnState: String? = null
        private var registered = false
        private var input: JsonObject? = null
        private var expectedToolNames = emptyList<String>()
        private val cleanupErrors = mutableListOf<String>()
        private val originalTools = c.toolPipeline.registry.all()
        private val disabled = mutableListOf<com.helix.tools.framework.ToolDescriptor>()

        suspend fun execute() {
            try {
                prepareInput()
                val tools = fixtureTools()
                createSession()
                registerFixture(tools)
                submitTurn()
                awaitFirstResponse()
            } catch (error: Exception) {
                failure = "${error.javaClass.simpleName}: ${error.message}"
                status = if (error.message.orEmpty().startsWith("BLOCKED:")) "BLOCKED" else "ERROR"
            } finally {
                cleanup()
                writeEvidence()
            }
            check(failure == null && cleanupErrors.isEmpty()) { "${failure.orEmpty()}; cleanup: $cleanupErrors" }
        }

        private fun prepareInput() {
            input =
                Json
                    .parseToJsonElement(
                        String(
                            Base64.decode(requireNotNull(args.getString("caseBase64")), Base64.NO_WRAP),
                            Charsets.UTF_8,
                        ),
                    ).jsonObject
            require(requireNotNull(input).getValue("id").jsonPrimitive.content == caseId)
            require(requireNotNull(input).keys == setOf("id", "question", "functions")) {
                "Only question and functions may enter fixture"
            }
            check(
                android.os.Build.MODEL
                    .contains("sdk_gphone") ||
                    android.os.Build.FINGERPRINT
                        .contains("generic"),
            ) {
                "BLOCKED: emulator-only fixture"
            }
            verifiedAvd =
                ParcelFileDescriptor
                    .AutoCloseInputStream(
                        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                            "getprop ro.boot.qemu.avd_name",
                        ),
                    ).bufferedReader()
                    .use { it.readText().trim() }
            check(verifiedAvd == "Helix_Benchmark_API36_20261108") {
                "BLOCKED: wrong isolated benchmark AVD"
            }
            check(
                c.storage.turns
                    .listActive()
                    .isEmpty(),
            ) { "BLOCKED: unrelated active turns" }
            check(originalTools.none { it.name.value.startsWith("mcp.$server.") }) {
                "BLOCKED: fixture namespace is already occupied"
            }
        }

        private fun fixtureTools(): List<com.helix.tools.framework.ToolDescriptor> {
            val prototype = requireNotNull(c.toolPipeline.resolveLatest("tools.search"))
            val tools =
                requireNotNull(input).getValue("functions").jsonArray.map { value ->
                    val function = value.jsonObject
                    val name = function.getValue("name").jsonPrimitive.content
                    require(name.matches(Regex("f[0-9]+")))
                    prototype.copy(
                        name = ToolName("mcp.$server.$name"),
                        description = function.getValue("description").jsonPrimitive.content,
                        inputSchema = function.getValue("parameters").jsonObject,
                        outputSchema = buildJsonObject {},
                        operationClass = ToolOperationClass.NETWORK,
                        origin = ToolOrigin.McpOrigin(server, "2025-03-26", "b".repeat(64)),
                        requiredCapabilities = emptySet(),
                    )
                }
            require(tools.isNotEmpty() && tools.map { it.name }.distinct().size == tools.size)
            expectedToolNames = tools.map { it.name.value }
            return tools
        }

        private suspend fun createSession() {
            provider =
                c.providerService.create(
                    ProviderDraft(
                        null,
                        "BFCL-$runId",
                        ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        NormalizedEndpoint.parse(endpoint),
                        model,
                        "{}",
                        false,
                    ),
                    null,
                )
            val probe = c.providerService.runConnectionTest(requireNotNull(provider))
            check(probe is com.helix.provider.api.ProbeOutcome.Ok) { "Provider connection failed: $probe" }
            session = c.chatService.createSession("BFCL-$caseId-$attempt", requireNotNull(provider), model)
            originalTools.forEach { tool ->
                c.storage.toolAvailability.set(
                    tool.origin.canonicalOf(),
                    tool.name.value,
                    ToolAvailabilityScope.SESSION,
                    requireNotNull(session),
                    ToolAvailabilityState.DISABLED,
                    System.currentTimeMillis(),
                )
                disabled += tool
            }
        }

        private fun registerFixture(tools: List<com.helix.tools.framework.ToolDescriptor>) {
            c.toolPipeline.registry.replaceMcpServer(
                server,
                tools.map { tool ->
                    ToolBinding(
                        tool,
                        object : ToolExecutor {
                            override fun execute(call: ExecutableToolCall): ToolExecutorResult =
                                if (call.cancel.isCancelled()) {
                                    ToolExecutorResult.Cancelled
                                } else {
                                    ToolExecutorResult.Completed(buildJsonObject { put("syntheticNoEffect", true) })
                                }
                        },
                    )
                },
            )
            registered = true
            permissionRevision =
                c.sessionPermissionEdit.saveSessionConfig(
                    requireNotNull(session),
                    SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS),
                    System.currentTimeMillis(),
                )
            permissionModeObserved =
                c.storage.sessionPermissionConfigs
                    .forSession(requireNotNull(session))
                    ?.mode
                    ?.name
            check(permissionModeObserved == SessionPermissionMode.FULL_ACCESS.name)
            check(c.storage.sessionPermissionConfigs.appDefault() == originalDefault)
        }

        private suspend fun submitTurn() {
            turnId =
                c.agentRuntime
                    .submit(
                        SubmitTurnCommand(
                            session = SessionId(requireNotNull(session)),
                            providerId = ProviderId(requireNotNull(provider)),
                            mode = AgentMode.ACT,
                            text = requireNotNull(input).getValue("question").jsonPrimitive.content,
                            budgets = TurnBudgets(maxOutputTokens = 4096),
                            clientRequestId = UUID.randomUUID().toString(),
                        ),
                    ).value
        }

        private suspend fun awaitFirstResponse() {
            withTimeout(180_000) {
                while (!captureFirstResponse()) delay(50)
            }
        }

        private fun captureFirstResponse(): Boolean {
            val turn = c.storage.turns.resolve(requireNotNull(turnId))
            observedTurnState = turn.state
            val first =
                c.storage.modelCalls
                    .listByTurn(requireNotNull(turnId))
                    .minByOrNull { it.ordinal }
            val awaiting =
                c.storage.toolCalls
                    .listByTurn(requireNotNull(turnId))
                    .filter { it.state == "AWAITING_APPROVAL" }
            if (awaiting.isNotEmpty()) {
                status = "BLOCKED"
                failure = "Approval required despite isolated FULL_ACCESS; no automatic approval"
                return true
            }
            val batch =
                c.storage.toolCalls
                    .listByTurn(requireNotNull(turnId))
                    .filter { it.modelCallId == first?.id }
            val settledBatch =
                batch.isNotEmpty() &&
                    batch.all {
                        com.helix.core.model.ToolCallState
                            .valueOf(it.state)
                            .isTerminal
                    }
            val captured = TurnState.valueOf(turn.state).isTerminal || settledBatch
            if (captured) status = "CAPTURED"
            return captured
        }

        private suspend fun cleanup() {
            var stopped = session == null
            session?.let { owned ->
                try {
                    stopAndJoinOwnedVisualCase(c, owned)
                    stopped = true
                } catch (error: Exception) {
                    cleanupErrors += "stop: ${error.javaClass.simpleName}: ${error.message}"
                }
            }
            if (stopped) restoreFixture()
        }

        private suspend fun restoreFixture() {
            try {
                if (registered) c.toolPipeline.registry.replaceMcpServer(server, emptyList())
                session?.let { owned ->
                    c.sessionPermissionEdit.resetSessionToDefault(owned, System.currentTimeMillis())
                    disabled.forEach { tool ->
                        c.storage.toolAvailability.remove(
                            tool.origin.canonicalOf(),
                            tool.name.value,
                            ToolAvailabilityScope.SESSION,
                            owned,
                        )
                    }
                }
                provider?.let { c.providerService.delete(it) }
                check(c.storage.sessionPermissionConfigs.appDefault() == originalDefault)
            } catch (error: Exception) {
                cleanupErrors += "restore: ${error.javaClass.simpleName}: ${error.message}"
            }
        }

        private fun verifyExposure(first: com.helix.core.storage.entity.ModelCallEntity?): Boolean {
            var firstExposureComplete = false
            if (first != null) {
                try {
                    val manifest = Json.parseToJsonElement(requireNotNull(first.requestManifest)).jsonObject
                    val names =
                        manifest.getValue("tools").jsonArray.map {
                            it.jsonArray
                                .first()
                                .jsonPrimitive.content
                        }
                    firstExposureComplete = expectedToolNames.isNotEmpty() && names.toSet() == expectedToolNames.toSet()
                    check(firstExposureComplete) { "First request did not expose all registered fixture tools" }
                } catch (error: Exception) {
                    status = "ERROR"
                    failure = "Adaptation exposure failure: ${error.message}"
                }
            }
            return firstExposureComplete
        }

        private fun writeEvidence() {
            val first =
                turnId?.let {
                    c.storage.modelCalls
                        .listByTurn(it)
                        .minByOrNull { row -> row.ordinal }
                }
            val raw =
                first?.toolMessageId?.let { messageId ->
                    c.storage.messages.readContent(c.storage.messages.resolve(messageId))
                }
            val calls = raw?.let { Json.parseToJsonElement(it).jsonArray } ?: JsonArray(emptyList())
            val responseComplete = first?.state == "COMPLETED" && first.settledAt != null
            if (capturedBeforeAssistance != null && capturedBeforeAssistance != raw) {
                status = "ERROR"
                failure = "First response changed after synthetic assistance"
            }
            val firstExposureComplete = verifyExposure(first)
            val evidence =
                buildJsonObject {
                    putAttemptIdentity()
                    put("firstModelCallId", first?.id)
                    put("firstModelCallState", first?.state)
                    put("firstResponseComplete", responseComplete)
                    put("firstExposureComplete", firstExposureComplete)
                    put("expectedToolNames", JsonArray(expectedToolNames.map(::JsonPrimitive)))
                    put("requestManifest", first?.requestManifest)
                    put("rawFirstToolMessage", raw)
                    put("firstResponseCalls", calls)
                    put("captureStage", "First Room response; provider-parsed, presentation metadata removed")
                    put("rawHttpAvailable", false)
                    put("syntheticApprovalsEnabled", syntheticApprovals)
                    put("permissionMode", permissionModeObserved)
                    put("permissionRevision", permissionRevision)
                    put("permissionScope", "owned synthetic-only session")
                    put("verifiedAvd", verifiedAvd)
                    put("appDefaultUnchanged", c.storage.sessionPermissionConfigs.appDefault() == originalDefault)
                    put("capturedBeforeAssistance", capturedBeforeAssistance != null)
                    put(
                        "approvalPolicy",
                        if (syntheticApprovals) {
                            "Normal per-call approval only for unchanged owned no-effect first-response bindings"
                        } else {
                            "Owned session FULL_ACCESS via normal session config; no automatic approval"
                        },
                    )
                    putApprovalEvidence()
                    putExecutionEvidence()
                }
            check(destination.parentFile!!.mkdirs() || destination.parentFile!!.isDirectory)
            val temporary = File(destination.parentFile, "${destination.name}.tmp")
            temporary.writeText(evidence.toString())
            check(temporary.renameTo(destination))
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.putApprovalEvidence() {
            put(
                "approvalRequests",
                JsonArray(
                    approvalRequests.map { (approvalId, callId) ->
                        val approval = c.storage.approvals.byToolCall(callId)
                        buildJsonObject {
                            put("approvalId", approvalId)
                            put("callId", callId)
                            put("turnId", turnId)
                            put("decision", approval?.decision)
                            put("decidedAt", approval?.decidedAt)
                            put("consumedAt", approval?.consumedAt)
                        }
                    },
                ),
            )
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.putAttemptIdentity() {
            put("runId", runId)
            put("caseId", caseId)
            put("attempt", attempt)
            put("input", input ?: kotlinx.serialization.json.JsonNull)
            put("model", model)
            put("endpoint", endpoint)
            put("status", status)
            put("failure", failure)
            put("cleanupErrors", JsonArray(cleanupErrors.map(::JsonPrimitive)))
            put("turnId", turnId)
            put("sessionId", session)
            put("turnState", observedTurnState)
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.putExecutionEvidence() {
            put("syntheticExecutor", true)
            put(
                "modelCallCount",
                turnId?.let {
                    c.storage.modelCalls
                        .listByTurn(it)
                        .size
                } ?: 0,
            )
            put(
                "dispatcherCalls",
                JsonArray(
                    turnId?.let { id -> c.storage.toolCalls.listByTurn(id) }.orEmpty().map {
                        buildJsonObject {
                            put("name", it.name)
                            put("arguments", it.argsJson)
                            put("modelCallId", it.modelCallId)
                            put("state", it.state)
                        }
                    },
                ),
            )
        }
    }
}
