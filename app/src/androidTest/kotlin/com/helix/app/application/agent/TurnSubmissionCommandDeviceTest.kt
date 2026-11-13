package com.helix.app.application.agent

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.helix.app.MainActivity
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.agent.SubmitTurnCommand
import com.helix.core.agent.TurnStartBlocked
import com.helix.core.agent.TurnSubmissionBlock
import com.helix.core.model.AgentMode
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderId
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SessionId
import com.helix.core.model.TurnBudgets
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Exercises the real AppAgentRuntime → application admission → Room → Engine composition. */
class TurnSubmissionCommandDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun explicitMissingSessionNeverFallsBackToOpenConversation() =
        withRuntime { provider, session, _ ->
            val container = compose.container()
            val unknown = command(provider, "missing-${UUID.randomUUID()}")
            val error =
                assertThrows(TurnStartBlocked::class.java) {
                    runBlocking { container.agentRuntime.submit(unknown) }
                }
            assertEquals(unknown.session.value, error.sessionId)
            assertEquals(TurnSubmissionBlock.SESSION_UNAVAILABLE, error.reason)
            assertTrue(
                container.storage.turns
                    .listBySession(session)
                    .isEmpty(),
            )
        }

    @Test
    fun archivedExplicitSessionCannotAdmitNewTurn() =
        withRuntime { provider, session, _ ->
            val container = compose.container()
            container.storage.sessions.archive(session, System.currentTimeMillis())
            val error =
                assertThrows(TurnStartBlocked::class.java) {
                    runBlocking { container.agentRuntime.submit(command(provider, session)) }
                }
            assertEquals(TurnSubmissionBlock.SESSION_UNAVAILABLE, error.reason)
            assertTrue(
                container.storage.turns
                    .listBySession(session)
                    .isEmpty(),
            )
        }

    @Test
    fun sameReceiptDeduplicatesAndDivergentInputIsTypedConflict() =
        withRuntime { provider, session, _ ->
            val container = compose.container()
            val input = command(provider, session)
            val original = container.agentRuntime.submit(input)
            assertEquals(original, container.agentRuntime.submit(input))
            val error =
                assertThrows(TurnStartBlocked::class.java) {
                    runBlocking { container.agentRuntime.submit(input.copy(text = "different input")) }
                }
            assertEquals(TurnSubmissionBlock.RECEIPT_CONFLICT, error.reason)
            assertEquals(
                listOf(original.value),
                container.storage.turns
                    .listBySession(session)
                    .map { it.id },
            )
            assertEquals(
                1,
                container.storage.modelCalls
                    .listByTurn(original.value)
                    .size,
            )
            assertEquals(
                1,
                container.storage.messages
                    .listBySession(session)
                    .count { it.role == "USER" },
            )
        }

    @Test fun queueUsesTargetPermissionsWithoutNewGoal(): Unit =
        withRuntime { provider, target, server ->
            val container = compose.container()
            val caller = delegationCaller(container, provider, "session.queue")
            try {
                val goalsBefore =
                    container.storage.goals
                        .list()
                        .map { it.id }
                        .toSet()
                container.storage.sessionAccess.setGrant(caller.sessionId, target, false, false, true)
                val requests = configureDelegatedRename(container, caller, target, server)
                val first = container.sessionDelegationService.queue(caller, target, "queue-key", "Rename this task.")
                val repeated =
                    container.sessionDelegationService.queue(
                        caller,
                        target,
                        "queue-key",
                        "Rename this task.",
                    )
                assertEquals(first.record.inputId, repeated.record.inputId)
                compose.waitUntil(20_000) {
                    container.storage.turns
                        .listBySession(target)
                        .singleOrNull()
                        ?.state == "COMPLETED"
                }
                assertQueuedDelegation(container, caller, target, first.record.inputId, goalsBefore, requests)
            } finally {
                closeDelegationCaller(container, caller)
            }
        }

    @Test fun steerBindsExpectedTurnAndRejectsTerminalRequest(): Unit =
        withRuntime { provider, target, server ->
            val container = compose.container()
            val caller = delegationCaller(container, provider, "session.steer")
            val arrived = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            try {
                container.storage.sessionAccess.setGrant(caller.sessionId, target, false, false, true)
                val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
                server.holdChatStreams.set(false)
                server.scriptedChat = { body ->
                    requests.add(body)
                    if (requests.size == 1) {
                        arrived.countDown()
                        check(release.await(15, java.util.concurrent.TimeUnit.SECONDS))
                    }
                    delegationTextStream()
                }
                val targetTurn = container.agentRuntime.submit(command(provider, target)).value
                check(arrived.await(10, java.util.concurrent.TimeUnit.SECONDS))
                val first =
                    container.sessionDelegationService.steer(
                        caller,
                        target,
                        targetTurn,
                        "steer-key",
                        "Also mention the scope.",
                    )
                release.countDown()
                compose.waitUntil(20_000) {
                    container.storage.turns
                        .resolve(targetTurn)
                        .state == "COMPLETED"
                }
                assertEquals(
                    listOf(targetTurn),
                    container.storage.turns
                        .listBySession(target)
                        .map { it.id },
                )
                val record = requireNotNull(container.storage.sessionInputs.get(first.record.inputId))
                assertEquals(targetTurn, record.consumedTurnId)
                assertEquals(com.helix.core.storage.repository.SessionInputState.APPENDED, record.state)
                assertTrue(requests.any { "[MODEL_DELEGATION]" in it && "Also mention the scope." in it })
                assertTerminalSteer(container, caller, target, targetTurn, first.record.inputId)
            } finally {
                release.countDown()
                closeDelegationCaller(container, caller)
            }
        }

    private fun withRuntime(block: suspend (String, String, LoopbackModelServer) -> Unit) =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider =
                    container.providerService.create(
                        ProviderDraft(
                            null,
                            "Application admission fixture",
                            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                            NormalizedEndpoint.parse("http://127.0.0.1:${server.port}/v1"),
                            "fixture-model-a",
                            "{}",
                            false,
                        ),
                        null,
                    )
                val session =
                    try {
                        check(container.providerService.runConnectionTest(provider) is ProbeOutcome.Ok)
                        chat.createSession("Application admission fixture", provider, "fixture-model-a")
                    } catch (failure: Throwable) {
                        container.providerService.delete(provider)
                        throw failure
                    }
                try {
                    server.holdChatStreams.set(true)
                    chat.openSession(session)
                    compose.waitUntil(10_000) { chat.screen.value.openSessionId == session }
                    block(provider, session, server)
                } finally {
                    container.storage.turns
                        .listBySession(session)
                        .forEach { chat.stopTurn(it.id) }
                    compose.waitUntil(10_000) {
                        container.storage.turns.listBySession(session).all {
                            com.helix.core.model.TurnState
                                .valueOf(it.state)
                                .isTerminal
                        }
                    }
                    chat.closeSession()
                    if (container.storage.sessions
                            .resolve(session)
                            .archivedAt == null
                    ) {
                        container.storage.sessions.archive(session, System.currentTimeMillis())
                    }
                    container.providerService.delete(provider)
                }
            }
        }

    private fun command(
        provider: String,
        session: String,
    ) = SubmitTurnCommand(
        session = SessionId(session),
        providerId = ProviderId(provider),
        mode = AgentMode.CHAT,
        text = "Synthetic admission check",
        budgets = TurnBudgets(maxOutputTokens = 1024),
        clientRequestId = UUID.randomUUID().toString(),
    )
}

private suspend fun delegationCaller(
    container: com.helix.app.AppContainer,
    provider: String,
    name: String,
): com.helix.core.storage.repository.SessionHistoryCaller {
    val source = container.chatService.createSession("Delegation source", provider, "fixture-model-a")
    var turn = container.storage.turns.start(UUID.randomUUID().toString(), source, System.currentTimeMillis())
    listOf(
        com.helix.core.model.TurnState.BUILDING_CONTEXT,
        com.helix.core.model.TurnState.WAITING_MODEL,
        com.helix.core.model.TurnState.RECEIVING_MODEL,
        com.helix.core.model.TurnState.RUNNING_TOOL,
    ).forEach {
        turn = container.storage.turns.updateState(turn, it, 0, null, null)
    }
    val call = UUID.randomUUID().toString()
    container.storage.toolCalls.append(call, turn.id, call, name, "1", "{}", "RUNNING")
    return com.helix.core.storage.repository
        .SessionHistoryCaller(source, turn.id, call, name)
}

private fun closeDelegationCaller(
    container: com.helix.app.AppContainer,
    caller: com.helix.core.storage.repository.SessionHistoryCaller,
) {
    container.storage.toolCalls.updateState(
        container.storage.toolCalls.resolve(caller.callId),
        com.helix.core.model.ToolCallState.COMPLETED,
    )
    val turn = container.storage.turns.resolve(caller.turnId)
    val stopping = container.storage.turns.updateState(turn, com.helix.core.model.TurnState.CANCELLING, 0, null, null)
    container.storage.turns.updateState(
        stopping,
        com.helix.core.model.TurnState.CANCELLED,
        0,
        System.currentTimeMillis(),
        null,
    )
    container.storage.sessions.archive(caller.sessionId, System.currentTimeMillis())
}

private fun delegationToolStream(
    name: String,
    args: org.json.JSONObject,
): String {
    val function =
        org.json
            .JSONObject()
            .put("name", name)
            .put("arguments", args.toString())
    return "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[" +
        "{\"id\":\"call-${UUID.randomUUID()}\",\"index\":0," +
        "\"type\":\"function\",\"function\":$function}]},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n"
}

private fun delegationTextStream(): String =
    "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Fixture complete\"}," +
        "\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"

private suspend fun configureDelegatedRename(
    container: com.helix.app.AppContainer,
    caller: com.helix.core.storage.repository.SessionHistoryCaller,
    target: String,
    server: LoopbackModelServer,
): List<String> {
    container.sessionPermissionEdit.saveSessionConfig(
        caller.sessionId,
        com.helix.core.policy.SessionPermissionConfig.of(
            com.helix.core.model.SessionPermissionMode.READ_ONLY,
        ),
        System.currentTimeMillis(),
    )
    container.sessionPermissionEdit.saveSessionConfig(
        target,
        com.helix.core.policy.SessionPermissionConfig.of(
            com.helix.core.model.SessionPermissionMode.FULL_ACCESS,
        ),
        System.currentTimeMillis(),
    )
    assertTrue(container.chatService.setModeFromComposer(target, AgentMode.GOAL))
    val originalTitle =
        container.storage.sessions
            .resolve(target)
            .title
    val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
    val step =
        java.util.concurrent.atomic
            .AtomicInteger()
    server.holdChatStreams.set(false)
    server.scriptedChat = { body ->
        requests.add(body)
        when (step.getAndIncrement()) {
            0 -> {
                delegationToolStream("tools.search", org.json.JSONObject().put("query", "session.rename"))
            }

            1 -> {
                delegationToolStream(
                    "session.rename",
                    org.json
                        .JSONObject()
                        .put("expectedTitle", originalTitle)
                        .put("title", "Delegated target rename"),
                )
            }

            else -> {
                delegationTextStream()
            }
        }
    }
    return requests
}

private fun assertQueuedDelegation(
    container: com.helix.app.AppContainer,
    caller: com.helix.core.storage.repository.SessionHistoryCaller,
    target: String,
    inputId: String,
    goalsBefore: Set<String>,
    requests: List<String>,
) {
    val turn =
        container.storage.turns
            .listBySession(target)
            .single()
    assertEquals(
        "Delegated target rename",
        container.storage.sessions
            .resolve(target)
            .title,
    )
    assertEquals(
        goalsBefore,
        container.storage.goals
            .list()
            .map { it.id }
            .toSet(),
    )
    assertEquals(null, container.storage.goalTurnBindings.byTurn(turn.id))
    val record = requireNotNull(container.storage.sessionInputs.get(inputId))
    assertEquals(com.helix.core.model.input.SessionInputOrigin.MODEL_DELEGATION, record.origin)
    assertEquals(turn.id, record.consumedTurnId)
    assertTrue(requests.any { "[MODEL_DELEGATION]" in it && caller.sessionId in it })
    assertEquals(
        "RUNNING_TOOL",
        container.storage.turns
            .resolve(caller.turnId)
            .state,
    )
}

private suspend fun assertTerminalSteer(
    container: com.helix.app.AppContainer,
    caller: com.helix.core.storage.repository.SessionHistoryCaller,
    target: String,
    targetTurn: String,
    inputId: String,
) {
    val repeated =
        container.sessionDelegationService.steer(
            caller,
            target,
            targetTurn,
            "steer-key",
            "Also mention the scope.",
        )
    assertEquals(inputId, repeated.record.inputId)
    assertThrows(IllegalArgumentException::class.java) {
        runBlocking {
            container.sessionDelegationService.steer(
                caller,
                target,
                targetTurn,
                "new-key",
                "Must not become a new Turn.",
            )
        }
    }
    assertEquals(
        1,
        container.storage.turns
            .listBySession(target)
            .size,
    )
}
