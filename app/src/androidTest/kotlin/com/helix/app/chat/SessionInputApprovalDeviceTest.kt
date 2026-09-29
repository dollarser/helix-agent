package com.helix.app.chat

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.helix.app.MainActivity
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.AgentMode
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolCallState
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.model.TurnState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.repository.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputState
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** Real model loop, dispatcher and broker coverage for Steer accepted during tool approval. */
class SessionInputApprovalDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Suppress("LongMethod") // One end-to-end approval/steer sequence proves ordering and non-execution together.
    fun steerWaitsForDeniedApprovalAndFollowsWholeBatchResults() =
        runBlocking {
            fixture { f ->
                val outcome =
                    f.chat
                        .sendSubmission(submission(f.session, INITIAL_TEXT))
                        .await()
                        .outcome
                assertTrue(outcome is ChatSubmissionOutcome.Accepted || outcome is ChatSubmissionOutcome.Enqueued)
                await("durable turn admission") {
                    f.storage.turns
                        .listBySession(f.session)
                        .size == 1
                }
                val receipt =
                    ChatSubmissionOutcome.Accepted(
                        f.storage.turns
                            .listBySession(f.session)
                            .single()
                            .id,
                    )
                await("mutation approval") {
                    f.storage.toolCalls
                        .listByTurn(receipt.turnId)
                        .any { it.name == ASK_TOOL && it.state == ToolCallState.AWAITING_APPROVAL.name }
                }
                val pendingCalls = f.storage.toolCalls.listByTurn(receipt.turnId)
                val safeCall = pendingCalls.single { it.name == SAFE_TOOL }
                val askCall = pendingCalls.single { it.name == ASK_TOOL }
                assertEquals(SAFE_INTENT, safeCall.modelIntent)
                assertEquals(ASK_INTENT, askCall.modelIntent)
                assertEquals("{}", safeCall.argsJson)
                assertEquals("{}", askCall.argsJson)
                val approval = requireNotNull(f.storage.approvals.byToolCall(askCall.id))
                assertNull(approval.decision)
                assertEquals(1, f.requests.size)

                val steer = enqueueWithoutResolvingApproval(f, receipt.turnId, approval.id, askCall.id)

                f.chat.denyApproval(approval.id)
                await("denied batch and steered response") {
                    f.requests.size >= 2 && !f.chat.screen.value.isSending
                }
                assertEquals(2, f.requests.size)
                assertEquals(
                    TurnState.COMPLETED.name,
                    f.storage.turns
                        .resolve(receipt.turnId)
                        .state,
                )
                assertEquals(
                    ToolCallState.COMPLETED.name,
                    f.storage.toolCalls
                        .resolve(safeCall.id)
                        .state,
                )
                assertEquals(
                    ToolCallState.DENIED.name,
                    f.storage.toolCalls
                        .resolve(askCall.id)
                        .state,
                )
                assertNotNull(f.storage.toolResults.byToolCall(safeCall.id))
                assertNotNull(f.storage.toolResults.byToolCall(askCall.id))
                assertEquals(1, f.safeExecutions.get())
                assertEquals(0, f.askExecutions.get())
                assertEquals(
                    "DENIED",
                    f.storage.approvals
                        .resolve(approval.id)
                        .decision,
                )
                val consumed = requireNotNull(f.storage.sessionInputs.get(steer.clientRequestId))
                assertEquals(SessionInputState.APPENDED, consumed.state)
                assertEquals(receipt.turnId, consumed.consumedTurnId)
                assertNotNull(consumed.requestModelCallId)
                assertWireOrder(f)
            }
        }

    private suspend fun enqueueWithoutResolvingApproval(
        fixture: Fixture,
        turnId: String,
        approvalId: String,
        callId: String,
    ): ChatSubmission {
        assertEquals(1, fixture.safeExecutions.get())
        assertEquals(0, fixture.askExecutions.get())
        val steer =
            submission(fixture.session, SUPPLEMENT).copy(
                delivery = SessionInputDelivery.STEER,
                expectedTurnId = turnId,
            )
        val outcome =
            fixture.chat
                .sendSubmission(steer)
                .await()
                .outcome
        assertTrue(outcome is ChatSubmissionOutcome.Enqueued)
        val input = requireNotNull(fixture.storage.sessionInputs.get(steer.clientRequestId))
        assertEquals(SessionInputState.PENDING, input.state)
        assertNull(input.messageId)
        assertNull(input.requestModelCallId)
        val approval = fixture.storage.approvals.resolve(approvalId)
        val call = fixture.storage.toolCalls.resolve(callId)
        assertNull(approval.decision)
        assertEquals(ToolCallState.AWAITING_APPROVAL.name, call.state)
        assertEquals(1, fixture.requests.size)
        return steer
    }

    private fun assertWireOrder(fixture: Fixture) {
        val first = fixture.requests[0]
        assertTrue(first.contains(SAFE_TOOL))
        assertTrue(first.contains(ASK_TOOL))
        assertTrue(first.contains(ToolPresentationMetadata.RESERVED_INTENT_KEY))
        assertFalse(first.contains(SUPPLEMENT))
        val messages =
            Json
                .parseToJsonElement(fixture.requests[1])
                .jsonObject
                .getValue("messages")
                .jsonArray
                .map { it.jsonObject }
        val assistantIndex =
            messages.indexOfFirst { message ->
                message.string("role") == "assistant" && message["tool_calls"] != null
            }
        val toolMessages = messages.withIndex().filter { it.value.string("role") == "tool" }
        val userIndex =
            messages.indexOfFirst { message ->
                message.string("role") == "user" && message.string("content") == SUPPLEMENT
            }
        assertTrue(assistantIndex >= 0)
        val assistantCalls =
            messages[assistantIndex]
                .getValue("tool_calls")
                .jsonArray
                .map {
                    it.jsonObject
                        .getValue("function")
                        .jsonObject
                        .string("arguments")
                }
        assertTrue(assistantCalls.all { ToolPresentationMetadata.RESERVED_INTENT_KEY !in it })
        assertEquals(listOf(SAFE_CALL, ASK_CALL), toolMessages.map { it.value.string("tool_call_id") })
        assertEquals(2, toolMessages.size)
        assertTrue(toolMessages.all { it.index > assistantIndex && it.index < userIndex })
        assertTrue(userIndex > toolMessages.last().index)
    }

    @Test
    fun executionFailureKeepsHarnessResultDespiteModelIntent() =
        runBlocking {
            fixture(failRead = true) { f ->
                val outcome =
                    f.chat
                        .sendSubmission(submission(f.session, INITIAL_TEXT))
                        .await()
                        .outcome
                assertTrue(outcome is ChatSubmissionOutcome.Accepted || outcome is ChatSubmissionOutcome.Enqueued)
                await("pending approval") {
                    f.storage.turns
                        .listBySession(f.session)
                        .flatMap { f.storage.toolCalls.listByTurn(it.id) }
                        .any { it.name == ASK_TOOL && it.state == ToolCallState.AWAITING_APPROVAL.name }
                }
                val turnId =
                    f.storage.turns
                        .listBySession(f.session)
                        .single()
                        .id
                val ask =
                    f.storage.toolCalls
                        .listByTurn(turnId)
                        .single { it.name == ASK_TOOL }
                f.chat.denyApproval(requireNotNull(f.storage.approvals.byToolCall(ask.id)).id)
                await("failed batch settles") {
                    f.storage.toolCalls.listByTurn(turnId).any {
                        it.name == SAFE_TOOL && it.state == ToolCallState.FAILED.name
                    } && !f.chat.screen.value.isSending
                }
                val failed =
                    f.storage.toolCalls
                        .listByTurn(turnId)
                        .single { it.name == SAFE_TOOL }
                assertEquals(SAFE_INTENT, failed.modelIntent)
                assertEquals("{}", failed.argsJson)
                val result = requireNotNull(f.storage.toolResults.byToolCall(failed.id))
                assertTrue(result.summary.contains("fixture execution failed"))
                assertEquals(
                    ToolCallState.FAILED.name,
                    f.storage.toolCalls
                        .resolve(failed.id)
                        .state,
                )
                assertEquals(0, f.askExecutions.get())
            }
        }

    @Suppress("LongMethod") // Registers, discovers and exercises the exact fixture tools under real authorization.
    private suspend fun fixture(
        failRead: Boolean = false,
        block: suspend (Fixture) -> Unit,
    ) {
        compose.resetDeterministicUiState()
        val container = compose.container()
        val chat = container.chatService
        val previous = chat.runControl.value
        val safeExecutions = AtomicInteger()
        val askExecutions = AtomicInteger()
        registerTool(
            container,
            SAFE_TOOL,
            ToolOperationClass.READ_ONLY,
            safeExecutions,
            failRead,
        )
        registerTool(
            container,
            ASK_TOOL,
            ToolOperationClass.LOCAL_MUTATION,
            askExecutions,
        )
        LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
            server.start()
            val provider = createProvider(container, server.port)
            val session = chat.createSession("Session input approval fixture", provider, MODEL)
            val requests = Collections.synchronizedList(mutableListOf<String>())
            try {
                container.sessionPermissionEdit.saveSessionConfig(
                    session,
                    SessionPermissionConfig.of(SessionPermissionMode.APPROVAL_REQUIRED),
                    System.currentTimeMillis(),
                )
                server.scriptedChat = { body ->
                    synchronized(requests) {
                        requests += body
                        if (requests.size == 1) approvalBatchStream() else textAnswerStream("batch settled")
                    }
                }
                chat.openSession(session)
                await("open fixture session") { chat.screen.value.openSessionId == session }
                chat.setMode(AgentMode.ACT)
                await("durable ACT configuration") {
                    container.storage.sessionRunControls
                        .forSession(session)
                        ?.mode == AgentMode.ACT
                }
                // Optional tools use the normal discovery window; fixture registration is not exposure.
                assertEquals(
                    2,
                    container.toolPipeline.mcpDiscovery
                        .search(session, "hxatest.input.approval", 2)
                        .size,
                )
                block(Fixture(container, chat, session, requests, safeExecutions, askExecutions))
            } finally {
                chat.stop()
                await("fixture cleanup") { !chat.screen.value.isSending }
                chat.closeSession()
                container.runControlStore.setMode(previous.mode)
                container.storage.sessions.archive(session, System.currentTimeMillis())
                container.providerService.delete(provider)
            }
        }
    }

    private fun registerTool(
        container: com.helix.app.AppContainer,
        name: String,
        operation: ToolOperationClass,
        executions: AtomicInteger,
        fail: Boolean = false,
    ) {
        val toolName = ToolName(name)
        val version =
            ToolVersion(
                (
                    container.toolPipeline.registry
                        .resolveLatest(toolName)
                        ?.version
                        ?.value ?: 0
                ) + 1,
            )
        val descriptor =
            ToolDescriptor(
                name = toolName,
                version = version,
                description = "Session input approval fixture",
                inputSchema = Json.parseToJsonElement(EMPTY_SCHEMA) as JsonObject,
                outputSchema = JsonObject(emptyMap()),
                operationClass = operation,
                timeout = 30.seconds,
                maxOutputBytes = 4096,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = ToolOrigin.BuiltInOrigin,
            )

        container.toolPipeline.registry.register(
            descriptor,
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    executions.incrementAndGet()
                    assertFalse(call.args.toString().contains(ToolPresentationMetadata.RESERVED_INTENT_KEY))
                    if (fail) return ToolExecutorResult.Failed("fixture execution failed", sideEffectFree = true)
                    return ToolExecutorResult.Completed(buildJsonObject { put("tool", name) })
                }
            },
        )
    }

    private suspend fun createProvider(
        container: com.helix.app.AppContainer,
        port: Int,
    ): String {
        val provider =
            container.providerService.create(
                ProviderDraft(
                    null,
                    "Session input approval fixture",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                    MODEL,
                    "{}",
                    false,
                    CleartextAuthorization("127.0.0.1", port),
                    emptyList(),
                ),
                null,
                cleartextConfirmed = true,
            )
        check(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
        check(container.providerService.runCapabilityTest(provider) is ProbeOutcome.Ok)
        return provider
    }

    private fun approvalBatchStream(): String =
        "data: {\"id\":\"approval-batch\",\"choices\":[{\"index\":0,\"delta\":{" +
            "\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[" +
            toolCallJson(SAFE_CALL, 0, SAFE_TOOL) + "," + toolCallJson(ASK_CALL, 1, ASK_TOOL) +
            "]},\"finish_reason\":null}]}\n\n" +
            "data: {\"id\":\"approval-batch\",\"choices\":[{\"index\":0," +
            "\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n" +
            "data: [DONE]\n\n"

    private fun toolCallJson(
        id: String,
        index: Int,
        name: String,
    ): String {
        val intent = if (name == SAFE_TOOL) SAFE_INTENT else ASK_INTENT
        val arguments = """{"__helix_intent":"$intent"}"""
        return "{\"id\":\"$id\",\"index\":$index,\"type\":\"function\"," +
            "\"function\":{\"name\":\"$name\",\"arguments\":" +
            Json.encodeToString(arguments) +
            "}}"
    }

    private fun submission(
        session: String,
        text: String,
    ): ChatSubmission = ChatSubmission(session, 0, UUID.randomUUID().toString(), text)

    private fun await(
        label: String,
        condition: () -> Boolean,
    ) {
        compose.waitUntil(30_000) { condition() }
        assertTrue(label, condition())
    }

    private data class Fixture(
        val container: com.helix.app.AppContainer,
        val chat: ChatService,
        val session: String,
        val requests: MutableList<String>,
        val safeExecutions: AtomicInteger,
        val askExecutions: AtomicInteger,
    ) {
        val storage = container.storage
    }

    private companion object {
        const val MODEL = "fixture-model-a"
        const val SAFE_TOOL = "hxatest.input.approval.read"
        const val ASK_TOOL = "hxatest.input.approval.mutate"
        const val SAFE_INTENT = "Read synthetic state"
        const val ASK_INTENT = "Apply synthetic mutation"
        const val SAFE_CALL = "approval-safe-call"
        const val ASK_CALL = "approval-denied-call"
        const val INITIAL_TEXT = "Run the approval batch"
        const val SUPPLEMENT = "Steer while approval is pending"
        const val EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"
    }
}

private fun JsonObject.string(key: String): String = get(key)?.jsonPrimitive?.content.orEmpty()
