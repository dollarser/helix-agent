package com.helix.app.chat

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.helix.app.MainActivity
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.OperationRule
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolCallState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real provider loop + Dispatcher + Room; compile-only until owner requests device validation. */
class WorkspaceRequestBindingDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun suspendedResponseAndPendingApprovalKeepOriginalDirectory() = scenario(deny = false)

    @Test fun currentDenyOverridesApprovalAfterDirectorySwitch() = scenario(deny = true)

    @Suppress("LongMethod") // One scenario follows capture, approval and actual filesystem outcome.
    private fun scenario(deny: Boolean) =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            val previous = chat.runControl.value
            val release = CountDownLatch(1)
            val requests = AtomicInteger()
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider =
                    container.providerService.create(
                        ProviderDraft(
                            null,
                            "Workspace request fixture",
                            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            NormalizedEndpoint.parse("http://127.0.0.1:${server.port}/v1"),
                            MODEL,
                            "{}",
                            false,
                            CleartextWarning("127.0.0.1", server.port),
                            emptyList(),
                        ),
                        null,
                    )
                check(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                check(container.providerService.runCapabilityTest(provider) is ProbeOutcome.Ok)
                val session = chat.createSession("Original workspace", provider, MODEL)
                val other = chat.createSession("Next workspace", provider, MODEL)
                try {
                    val original = requireNotNull(storage.workspaces.binding(session))
                    val next = requireNotNull(storage.workspaces.binding(other))
                    val originalRoot = storage.workspaces.managedDirectory(original.workspaceId)
                    val nextRoot = storage.workspaces.managedDirectory(next.workspaceId)
                    Files.write(originalRoot.resolve("AGENTS.md"), "HXA210_ORIGINAL_INSTRUCTIONS".toByteArray())
                    Files.write(nextRoot.resolve("AGENTS.md"), "HXA210_NEXT_INSTRUCTIONS".toByteArray())
                    container.sessionPermissionEdit.saveSessionConfig(
                        session,
                        SessionPermissionConfig.of(SessionPermissionMode.APPROVAL_REQUIRED),
                        System.currentTimeMillis(),
                    )
                    server.scriptedChat = {
                        if (requests.incrementAndGet() == 1) {
                            check(release.await(30, TimeUnit.SECONDS)) { "Fixture response was not released" }
                            writeStream()
                        } else {
                            textAnswerStream("settled")
                        }
                    }
                    chat.openSession(session)
                    chat.setMode(AgentMode.ACT)
                    compose.waitUntil(30_000) {
                        storage.sessionRunControls.forSession(session)?.mode == AgentMode.ACT &&
                            chat.runControl.value.mode == AgentMode.ACT
                    }
                    val receipt =
                        chat
                            .sendSubmission(
                                ChatSubmission(
                                    session,
                                    0,
                                    UUID.randomUUID().toString(),
                                    "Write the fixture file",
                                ),
                            ).await()
                            .outcome
                    assertTrue(receipt is ChatSubmissionOutcome.Accepted || receipt is ChatSubmissionOutcome.Enqueued)
                    try {
                        compose.waitUntil(30_000) { requests.get() == 1 }
                    } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                        throw AssertionError(
                            "Request not reached: count=${requests.get()}, admission=$receipt, " +
                                "turns=${storage.turns.listBySession(session)}, " +
                                "input=${(receipt as? ChatSubmissionOutcome.Enqueued)?.let {
                                    storage.sessionInputs.get(it.inputId)
                                }}, " +
                                "sending=${chat.screen.value.isSending}",
                            failure,
                        )
                    }
                    assertTrue(requireNotNull(server.lastChatRequest.get()).contains("HXA210_ORIGINAL_INSTRUCTIONS"))
                    assertFalse(requireNotNull(server.lastChatRequest.get()).contains("HXA210_NEXT_INSTRUCTIONS"))
                    val turnId =
                        storage.turns
                            .listBySession(session)
                            .single()
                            .id
                    chat.setSessionDirectory("scope:${next.workspaceId}:")
                    compose.waitUntil(30_000) { storage.workspaces.binding(session)?.workspaceId == next.workspaceId }
                    release.countDown()
                    compose.waitUntil(30_000) {
                        storage.toolCalls
                            .listByTurn(
                                turnId,
                            ).any { it.state == ToolCallState.AWAITING_APPROVAL.name }
                    }
                    val call = storage.toolCalls.listByTurn(turnId).single()
                    val frozenPath = "scope:${original.workspaceId}:fixture.txt"
                    assertEquals(
                        frozenPath,
                        Json
                            .parseToJsonElement(call.argsJson)
                            .jsonObject["path"]!!
                            .jsonPrimitive.content,
                    )
                    val approval = requireNotNull(storage.approvals.byToolCall(call.id))
                    if (deny) {
                        val rules =
                            SessionPermissionConfig.of(SessionPermissionMode.APPROVAL_REQUIRED).rules.mapValues {
                                OperationRule.DENY
                            }
                        container.sessionPermissionEdit.saveSessionConfig(
                            session,
                            SessionPermissionConfig.custom(rules),
                            System.currentTimeMillis(),
                        )
                    }
                    chat.approveApproval(approval.id)
                    compose.waitUntil(30_000) { !chat.screen.value.isSending }
                    assertFalse(Files.exists(nextRoot.resolve("fixture.txt")))
                    assertEquals(call.argsJson, storage.toolCalls.resolve(call.id).argsJson)
                    if (deny) {
                        assertFalse(Files.exists(originalRoot.resolve("fixture.txt")))
                        assertTrue(storage.toolCalls.resolve(call.id).state != ToolCallState.COMPLETED.name)
                    } else {
                        assertEquals(
                            storage.toolResults.byToolCall(call.id).toString(),
                            ToolCallState.COMPLETED.name,
                            storage.toolCalls.resolve(call.id).state,
                        )
                        assertEquals("original", String(Files.readAllBytes(originalRoot.resolve("fixture.txt"))))
                        assertEquals(
                            frozenPath,
                            storage.artifacts
                                .listByTurn(turnId)
                                .single()
                                .relativePath,
                        )
                        val beforeNextRequest = requests.get()
                        val nextReceipt =
                            chat
                                .sendSubmission(
                                    ChatSubmission(
                                        session,
                                        0,
                                        UUID.randomUUID().toString(),
                                        "Read the updated project instructions",
                                    ),
                                ).await()
                                .outcome
                        try {
                            compose.waitUntil(30_000) {
                                requests.get() > beforeNextRequest && !chat.screen.value.isSending
                            }
                        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                            throw AssertionError(
                                "Next request did not settle: count=${requests.get()}, before=$beforeNextRequest, " +
                                    "admission=$nextReceipt, turns=${storage.turns.listBySession(session)}, " +
                                    "input=${(nextReceipt as? ChatSubmissionOutcome.Enqueued)?.let {
                                        storage.sessionInputs.get(it.inputId)
                                    }}, screen=${chat.screen.value}",
                                failure,
                            )
                        }
                        assertTrue(requireNotNull(server.lastChatRequest.get()).contains("HXA210_NEXT_INSTRUCTIONS"))
                        assertFalse(
                            requireNotNull(server.lastChatRequest.get()).contains("HXA210_ORIGINAL_INSTRUCTIONS"),
                        )
                    }
                } finally {
                    release.countDown()
                    chat.stop()
                    compose.waitUntil(30_000) { !chat.screen.value.isSending }
                    chat.closeSession()
                    container.runControlStore.setMode(previous.mode)
                    storage.sessions.archive(session, System.currentTimeMillis())
                    storage.sessions.archive(other, System.currentTimeMillis())
                    container.providerService.delete(provider)
                }
            }
        }

    private fun writeStream(): String {
        val args = Json.encodeToString("""{"path":"fixture.txt","content":"original"}""")
        return "data: {\"id\":\"workspace-write\",\"choices\":[{\"index\":0,\"delta\":{" +
            "\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"write-call\",\"index\":0,\"type\":\"function\"," +
            "\"function\":{\"name\":\"write\",\"arguments\":$args}}]},\"finish_reason\":null}]}\n\n" +
            "data: {\"id\":\"workspace-write\",\"choices\":[{\"index\":0,\"delta\":{}," +
            "\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
    }

    private companion object {
        const val MODEL = "fixture-model-a"
    }
}
