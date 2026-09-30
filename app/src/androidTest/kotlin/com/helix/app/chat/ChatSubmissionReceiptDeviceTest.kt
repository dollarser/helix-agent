package com.helix.app.chat

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.helix.app.MainActivity
import com.helix.app.R
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.api.ProbeOutcome
import com.helix.tools.framework.ToolRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class ChatSubmissionReceiptDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun immediateSendWaitsForEarlierModeAndBudgetEdits() =
        runBlocking {
            val container = compose.container()
            val chat = container.chatService
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider = createProvider(server.port)
                try {
                    repeat(5) { index ->
                        val session = chat.createSession("Ordered send $index", provider, "fixture-model-a")
                        try {
                            chat.openSession(session)
                            val budgets =
                                com.helix.core.model
                                    .TurnBudgets(8, 6, 131072, 4096, 131072)
                            chat.setMode(com.helix.core.model.AgentMode.PLAN)
                            chat.setTurnBudgets(budgets)
                            val request = ChatSubmission(session, 0, UUID.randomUUID().toString(), "Echo probe.")
                            // No waiting for UI/Room between edits and Send: user action order is the contract.
                            val receipt = chat.sendSubmission(request).await()
                            assertTrue(receipt.toString(), receipt.outcome is ChatSubmissionOutcome.Accepted)
                            val input = requireNotNull(container.storage.sessionInputs.get(request.clientRequestId))
                            assertEquals("PLAN", input.configuration.mode)
                            assertNotNull(input.consumedTurnId)
                            assertEquals(
                                budgets,
                                container.storage.sessionRunControls
                                    .forSession(session)
                                    ?.budgets,
                            )
                        } finally {
                            chat.stop()
                            compose.waitUntil(10_000) { !chat.screen.value.isSending }
                            chat.closeSession()
                            container.storage.sessions.archive(session, System.currentTimeMillis())
                        }
                    }
                } finally {
                    container.providerService.delete(provider)
                }
            }
        }

    @Test
    fun unverifiedProviderRejectsSubmissionWithoutConsumingSavedDraft() =
        runBlocking {
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider = createProvider(server.port, verify = false)
                val session = UUID.randomUUID().toString()
                storage.sessions.create(session, "Unverified provider draft", provider, null, 1)
                try {
                    chat.openSession(session)
                    val request = ChatSubmission(session, 0, UUID.randomUUID().toString(), "Keep this request")
                    assertTrue(chat.saveComposerDraft(request))
                    val receipt = chat.sendSubmission(request).await()
                    assertEquals(
                        ChatSubmissionOutcome.Rejected(
                            compose.activity.getString(R.string.chat_blocked_provider_untested),
                        ),
                        receipt.outcome,
                    )
                    assertEquals(request, chat.loadComposerDraft(session))
                    assertTrue(storage.turns.listBySession(session).isEmpty())
                } finally {
                    chat.closeSession()
                    storage.sessions.archive(session, System.currentTimeMillis())
                    container.providerService.delete(provider)
                }
            }
        }

    @Test
    fun emptyInputRejectsSubmissionWithoutConsumingSavedDraft() =
        runBlocking {
            val container = compose.container()
            val chat = container.chatService
            val session = UUID.randomUUID().toString()
            container.storage.sessions.create(session, "Invalid input draft", null, null, 1)
            try {
                chat.openSession(session)
                val request = ChatSubmission(session, 0, UUID.randomUUID().toString(), "   ")
                assertTrue(chat.saveComposerDraft(request))
                assertEquals(
                    ChatSubmissionOutcome.Rejected("INVALID_INPUT"),
                    chat.sendSubmission(request).await().outcome,
                )
                assertEquals(request, chat.loadComposerDraft(session))
                assertTrue(
                    container.storage.turns
                        .listBySession(session)
                        .isEmpty(),
                )
            } finally {
                chat.closeSession()
                container.storage.sessions.archive(session, System.currentTimeMillis())
            }
        }

    @Test
    fun switchingToANewConversationSavesThePreviousComposerBeforeNavigation() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            compose.waitUntil(10_000) { chat.screen.value.isDraft }
            val session = requireNotNull(chat.screen.value.openSessionId)
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("chat-input").fetchSemanticsNodes().singleOrNull()?.let {
                    it.config.getOrNull(SemanticsProperties.Disabled) == null
                } == true
            }
            compose.onNodeWithTag("chat-input").performTextInput("Keep this new draft")
            compose.onNodeWithTag("chat-input").assertTextEquals("Keep this new draft")
            assertEquals("Input must remain bound to the selected session", session, chat.screen.value.openSessionId)
            compose.onNodeWithTag("chat-new-session").performClick()
            compose.waitUntil(10_000) {
                chat.screen.value.isDraft && chat.screen.value.openSessionId != session
            }
            assertEquals("Keep this new draft", chat.loadComposerDraft(session)?.text)
            assertTrue(
                container.storage.turns
                    .listBySession(session)
                    .isEmpty(),
            )
            chat.openSession(session)
            compose.waitUntil(10_000) {
                compose
                    .onAllNodesWithTag("chat-input")
                    .fetchSemanticsNodes()
                    .singleOrNull()
                    ?.config
                    ?.getOrNull(SemanticsProperties.EditableText)
                    ?.text == "Keep this new draft"
            }
            compose.onNodeWithTag("chat-input").assertTextEquals("Keep this new draft")
            chat.closeSession()
            container.storage.sessions.archive(session, System.currentTimeMillis())
        }

    @Test
    fun rejectedSubmissionPreservesComposerInputAndMapsSafeReason() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            // Create session with no provider bound (providerId = null)
            val sessionId = UUID.randomUUID().toString()
            storage.sessions.create(sessionId, "Unbound session", null, null, 1)
            try {
                chat.openSession(sessionId)
                compose.waitUntil(10_000) { chat.screen.value.openSessionId == sessionId }

                compose.onNodeWithTag("chat-input").performTextInput("Draft text to preserve")
                compose.onNodeWithTag("chat-send").performClick()

                // Submission is rejected because NO_PROVIDER
                compose.waitUntil(10_000) {
                    chat.screen.value.blockedReason != null
                }

                // Verify input was preserved on screen
                compose.onNodeWithTag("chat-input").assertTextEquals("Draft text to preserve")
                // Verify blocked reason is human-readable localized message, not raw internal code
                val blocked = chat.screen.value.blockedReason
                assertNotNull(blocked)
                assertFalse(blocked!!.contains("NO_PROVIDER"))
                assertFalse(blocked.contains("ADMISSION_FAILED"))
                assertEquals(compose.activity.getString(R.string.chat_blocked_no_provider_bound), blocked)
            } finally {
                chat.closeSession()
                storage.sessions.archive(sessionId, System.currentTimeMillis())
            }
        }

    @Test
    fun acceptedSubmissionClearsComposerAndAcknowledgesDraft() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val providerId = createProvider(server.port)
                val session = chat.createSession("Accepted receipt session", providerId, "fixture-model-a")
                try {
                    chat.openSession(session)
                    compose.waitUntil(10_000) { chat.screen.value.openSessionId == session }

                    compose.onNodeWithTag("chat-input").performTextInput("Hello Assistant")
                    // Two immediate activations of the same UI action keep one immutable intent.
                    compose.onNodeWithTag("chat-send").performSemanticsAction(SemanticsActions.OnClick) { click ->
                        click()
                        click()
                    }

                    // Wait for accepted turn and input cleared
                    compose.waitUntil(10_000) {
                        chat.screen.value.activeTurn != null &&
                            chat.screen.value.messages
                                .any { it.role == "user" }
                    }

                    // On Accepted, the screen input should be cleared
                    compose.waitUntil(10_000) {
                        compose
                            .onNodeWithTag("chat-input")
                            .fetchSemanticsNode()
                            .config[SemanticsProperties.EditableText]
                            .text
                            .isEmpty()
                    }
                    compose.onNodeWithTag("chat-input").assert(
                        SemanticsMatcher("editable text is empty") {
                            it.config.getOrNull(SemanticsProperties.EditableText)?.text == ""
                        },
                    )
                    // The per-session input file should be acknowledged and cleared
                    compose.waitUntil(10_000) {
                        storage.composerDrafts.get(session) == null
                    }
                    assertNull(storage.composerDrafts.get(session))
                    assertEquals(1, storage.turns.listBySession(session).size)
                } finally {
                    storage.turns.listBySession(session).forEach { chat.stopTurn(it.id) }
                    compose.waitUntil(10_000) { !chat.screen.value.isSending }
                    chat.closeSession()
                    storage.sessions.archive(session, System.currentTimeMillis())
                    container.providerService.delete(providerId)
                }
            }
        }

    @Test
    fun sessionSwitchDoesNotClearDifferentSessionOnLateReceipt() =
        runBlocking {
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            val sessionA = UUID.randomUUID().toString()
            val sessionB = UUID.randomUUID().toString()
            storage.sessions.create(sessionA, "Session A", null, null, 1)
            storage.sessions.create(sessionB, "Session B", null, null, 2)
            try {
                // Draft in A
                val draftA = ChatSubmission(sessionA, 0L, "req-a", "Draft in session A")
                assertTrue(chat.saveComposerDraft(draftA))

                // Draft in B
                val draftB = ChatSubmission(sessionB, 0L, "req-b", "Draft in session B")
                assertTrue(chat.saveComposerDraft(draftB))

                // Open B
                chat.openSession(sessionB)
                val loadedB = chat.loadComposerDraft(sessionB)
                assertEquals("Draft in session B", loadedB?.text)

                // Suppose receipt for A arrives as accepted for turn-a
                val receiptA = ChatSubmissionReceipt(draftA, ChatSubmissionOutcome.Accepted("turn-a"))
                assertFalse(chat.acknowledgeSubmission(receiptA))

                // B's draft is untouched
                val currentB = storage.composerDrafts.get(sessionB)?.toSubmission()
                assertEquals(draftB, currentB)
            } finally {
                chat.closeSession()
                storage.sessions.archive(sessionA, System.currentTimeMillis())
                storage.sessions.archive(sessionB, System.currentTimeMillis())
            }
        }

    @Test
    fun editedDraftDoesNotClearOnStaleReceipt() =
        runBlocking {
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            val session = UUID.randomUUID().toString()
            storage.sessions.create(session, "Session Edit", null, null, 1)
            try {
                val draftV0 = ChatSubmission(session, 0L, "req-v0", "Initial draft")
                assertTrue(chat.saveComposerDraft(draftV0))

                // Edit to v1
                val draftV1 = ChatSubmission(session, 1L, "req-v1", "Edited draft")
                assertTrue(chat.saveComposerDraft(draftV1))

                // Stale v0 receipt arrives
                val receiptV0 = ChatSubmissionReceipt(draftV0, ChatSubmissionOutcome.Accepted("turn-v0"))
                assertFalse(chat.acknowledgeSubmission(receiptV0))

                // v1 draft remains intact
                val current = storage.composerDrafts.get(session)?.toSubmission()
                assertEquals(draftV1, current)
            } finally {
                storage.sessions.archive(session, System.currentTimeMillis())
            }
        }

    @Test
    fun duplicateSubmissionDeduplicatesWithoutStartingSecondTurn() =
        runBlocking {
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val providerId = createProvider(server.port)
                val session = chat.createSession("Dedup session", providerId, "fixture-model-a")
                try {
                    chat.openSession(session)
                    val reqId = UUID.randomUUID().toString()
                    val submission = ChatSubmission(session, 0L, reqId, "Dedup message")

                    val receipt1 = chat.sendSubmission(submission).await()
                    val turn1 = compose.awaitAdmittedTurn(receipt1)

                    // Second send with same snapshot
                    val receipt2 = chat.sendSubmission(submission).await()
                    val turn2 = compose.awaitAdmittedTurn(receipt2)

                    assertEquals(turn1, turn2)
                    assertEquals(1, storage.turns.listBySession(session).size)
                } finally {
                    storage.turns.listBySession(session).forEach { chat.stopTurn(it.id) }
                    compose.waitUntil(10_000) { !chat.screen.value.isSending }
                    chat.closeSession()
                    storage.sessions.archive(session, System.currentTimeMillis())
                    container.providerService.delete(providerId)
                }
            }
        }

    @Test
    fun regenerateButtonUsesBackgroundStorageAndRetainsOneUserMessage() =
        runBlocking {
            compose.resetDeterministicUiState()
            val chat = compose.container().chatService
            val storage = compose.container().storage
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider = createProvider(server.port)
                // Provider probing is complete; ordinary answers must not replay the probe's echo call.
                server.forceTextResponses = true
                val session = chat.createSession("Regenerate UI", provider, "fixture-model-a")
                try {
                    compose.runOnUiThread { chat.openSession(session) }
                    compose.waitUntil(
                        10_000,
                    ) { chat.screen.value.openSessionId == session && !chat.screen.value.isDraft }
                    compose.onNodeWithTag("chat-input").performTextInput("Reply briefly")
                    compose.onNodeWithTag("chat-input").assertTextEquals("Reply briefly")
                    compose.onNodeWithTag("chat-send").performClick()
                    awaitRegenerateAnswer(session)
                    val answer =
                        chat.screen.value.messages
                            .last { it.role == "assistant" }
                            .id
                    compose
                        .onNodeWithTag(
                            "chat-timeline",
                        ).performScrollToNode(hasTestTag("chat-message-toggle-$answer"))
                    compose.onNodeWithTag("chat-message-toggle-$answer").performClick()
                    compose.onNodeWithTag("chat-regenerate-$answer").performClick()
                    awaitRegenerateAnswer(session, answer)
                    val turns = storage.turns.listBySession(session)
                    assertEquals(2, turns.size)
                    assertTrue(turns.all { it.state == "COMPLETED" })
                    assertNotNull(storage.messages.resolve(answer).supersededBy)
                    assertEquals(1, storage.messages.listBySession(session).count { it.role == "USER" })
                } finally {
                    chat.stop()
                    chat.closeSession()
                    compose.container().providerService.delete(provider)
                }
            }
        }

    @Test
    @Suppress("LongMethod") // Executor proposal, session boundary, visible confirmation and atomic rejection.
    fun settingsMutationRequiresAuthorizedApplyInsteadOfLegacyProposal() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider = createProvider(server.port)
                val session = chat.createSession("Settings proposal", provider, "fixture-model-a")
                try {
                    compose.runOnUiThread { chat.openSession(session) }
                    compose.waitUntil(
                        10_000,
                    ) { chat.screen.value.openSessionId == session && !chat.screen.value.isDraft }
                    val before = chat.runControl.value.mode
                    val proposal =
                        com.helix.app.settings.HelixSettingsRequests.Proposal(
                            "settings-test",
                            session,
                            buildJsonObject {
                                put("action", "propose")
                                put("mode", "PLAN")
                                put("reasoning", "OFF")
                                put("providerId", provider)
                            },
                        )
                    val registry =
                        com.helix.tools.framework
                            .ToolRegistry()
                    com.helix.app.settings.HelixSettingsTool
                        .register(registry) { container }
                    val executor =
                        registry.executor(
                            com.helix.core.model
                                .ToolName("helix.settings"),
                            com.helix.core.model
                                .ToolVersion(1),
                        )
                    val call =
                        com.helix.tools.framework.ExecutableToolCall(
                            proposal.id,
                            "helix.settings",
                            "1",
                            proposal.values,
                            com.helix.core.model.ExecutionTargetType.LOCAL_ANDROID,
                            java.time.Instant
                                .now()
                                .plusSeconds(30),
                            object : com.helix.tools.framework.CancelSignal {
                                override fun isCancelled() = false
                            },
                            session,
                        )
                    assertEquals(
                        com.helix.tools.framework.ToolExecutorResult.TimedOut,
                        executor.execute(call.copy(deadline = java.time.Instant.EPOCH)),
                    )
                    assertTrue(requireNotNull(container.settingsRequests).pending.value.isEmpty())
                    assertTrue(executor.execute(call) is com.helix.tools.framework.ToolExecutorResult.Failed)
                    assertTrue(requireNotNull(container.settingsRequests).pending.value.isEmpty())
                    assertEquals(before, chat.runControl.value.mode)
                    val applyExecutor =
                        registry.executor(
                            com.helix.core.model
                                .ToolName("helix.settings.apply"),
                            com.helix.core.model
                                .ToolVersion(1),
                        )
                    val applyCall =
                        call.copy(
                            toolName = "helix.settings.apply",
                            args = buildJsonObject { put("mode", "PLAN") },
                            turnId = "stale-turn",
                        )
                    assertTrue(applyExecutor.execute(applyCall) is com.helix.tools.framework.ToolExecutorResult.Failed)
                    assertEquals(before, chat.runControl.value.mode)
                    assertFalse(
                        chat.applyUserSettings(
                            "different-session",
                            com.helix.core.model.AgentMode.PLAN,
                            null,
                            null,
                            null,
                        ),
                    )
                    assertTrue(chat.applyUserSettings(session, com.helix.core.model.AgentMode.PLAN, null, null, null))
                    assertEquals(com.helix.core.model.AgentMode.PLAN, chat.runControl.value.mode)
                    assertFalse(chat.applyUserSettings(session, null, null, "missing-model", null))
                    assertEquals(com.helix.core.model.AgentMode.PLAN, chat.runControl.value.mode)
                    assertEquals(com.helix.core.model.SafetyProfile.STANDARD, container.profileStore.profile)
                } finally {
                    container.settingsRequests?.pending?.value?.filter { it.sessionId == session }?.forEach {
                        container.settingsRequests?.remove(it.id)
                    }
                    chat.closeSession()
                    container.providerService.delete(provider)
                }
            }
        }

    private fun awaitRegenerateAnswer(
        session: String,
        previous: String? = null,
    ) {
        val container = compose.container()
        runCatching {
            compose.waitUntil(15_000) {
                val screen = container.chatService.screen.value
                !screen.isSending && screen.messages.any { it.role == "assistant" && it.id != previous }
            }
        }.getOrElse { failure ->
            val screen = container.chatService.screen.value
            val turns =
                container.storage.turns
                    .listBySession(session)
                    .map { it.state }
            throw AssertionError(
                "Regenerate fixture: sessionMatches=${screen.openSessionId == session}, " +
                    "draft=${screen.isDraft}, preparing=${screen.preparingDraft}, sending=${screen.isSending}, " +
                    "blocked=${screen.blockedReason}, disclosure=${screen.pendingDisclosure != null}, turns=$turns",
                failure,
            )
        }
    }

    @Test
    @Suppress("LongMethod") // Real question UI, independent composer and durable answer identity.
    fun questionCustomAnswerSurvivesReopenWithoutConsumingIndependentDraft() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val questions = requireNotNull(container.userQuestions)
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                val provider = createProvider(server.port)
                val session = chat.createSession("Question UI", provider, "fixture-model-a")
                val questionId = "question-ui-${UUID.randomUUID()}"
                val draft = ChatSubmission(session, 0, UUID.randomUUID().toString(), "Keep my independent draft")
                try {
                    assertTrue(chat.saveComposerDraft(draft))
                    questions.offer(
                        questionId,
                        session,
                        null,
                        buildJsonObject {
                            put("question", "Which output format?")
                            put(
                                "options",
                                kotlinx.serialization.json.JsonArray(
                                    listOf(
                                        kotlinx.serialization.json.JsonPrimitive("PDF"),
                                        kotlinx.serialization.json.JsonPrimitive("Markdown"),
                                    ),
                                ),
                            )
                        },
                    )
                    chat.openSession(session)
                    compose.waitUntil(10_000) {
                        chat.screen.value.openSessionId == session && !chat.screen.value.isDraft
                    }
                    compose.onNodeWithText("Which output format?").assertIsDisplayed()
                    compose.onNodeWithText("PDF").performClick()
                    compose
                        .onNodeWithText(compose.activity.getString(R.string.user_question_custom))
                        .performTextInput("Plain text please")
                    compose.onNodeWithText(compose.activity.getString(R.string.user_question_send)).performClick()
                    compose.waitUntil(15_000) {
                        container.storage.sessionInputs
                            .get("answer:$questionId")
                            ?.consumedTurnId != null &&
                            !chat.screen.value.isSending
                    }
                    assertEquals(draft.text, chat.loadComposerDraft(session)?.text)
                    val answer = requireNotNull(container.storage.sessionInputs.get("answer:$questionId"))
                    assertEquals(
                        "Which output format?\nPlain text please",
                        container.storage.sessionInputs.readText(answer),
                    )
                    assertTrue(questions.pending(session).isEmpty())
                    chat.closeSession()
                    chat.openSession(session)
                    compose.waitUntil(10_000) { chat.screen.value.openSessionId == session }
                    assertTrue(questions.pending(session).isEmpty())
                    assertEquals(
                        1,
                        container.storage.turns
                            .listBySession(session)
                            .size,
                    )
                    assertEquals(draft.text, chat.loadComposerDraft(session)?.text)
                } finally {
                    container.storage.turns
                        .listBySession(session)
                        .forEach { chat.stopTurn(it.id) }
                    compose.waitUntil(10_000) { !chat.screen.value.isSending }
                    chat.closeSession()
                    container.storage.sessions.archive(session, System.currentTimeMillis())
                    container.providerService.delete(provider)
                }
            }
        }

    private suspend fun createProvider(
        port: Int,
        verify: Boolean = true,
    ): String {
        val service = compose.container().providerService
        val id =
            service.create(
                ProviderDraft(
                    null,
                    "Submission receipt fixture",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                    "fixture-model-a",
                    "{}",
                    false,
                    CleartextAuthorization("127.0.0.1", port),
                    emptyList(),
                ),
                null,
                cleartextConfirmed = true,
            )
        try {
            if (verify) check(service.runConnectionTest(id) is ProbeOutcome.Ok)
        } catch (failure: Throwable) {
            service.delete(id)
            throw failure
        }
        return id
    }
}
