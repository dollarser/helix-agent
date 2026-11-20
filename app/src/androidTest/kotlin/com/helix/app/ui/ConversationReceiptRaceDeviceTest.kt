package com.helix.app.ui

import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.MainActivity
import com.helix.app.agent.TurnStartSpec
import com.helix.app.application.agent.TurnInputFingerprint
import com.helix.app.provider.LoopbackModelServer
import com.helix.app.provider.ProviderDraft
import com.helix.app.test.TransferTestDocumentsProvider
import com.helix.app.test.settleFixtureTerminal
import com.helix.app.test.startFixtureTurn
import com.helix.core.agent.ModelStreamTerminal
import com.helix.core.agent.turn.TurnRoundCoordinator
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnState
import com.helix.core.storage.repository.MessageAttachmentRepository
import com.helix.feature.conversation.application.ChatService
import com.helix.feature.conversation.input.ChatSubmission
import com.helix.feature.conversation.input.ChatSubmissionOutcome
import com.helix.feature.conversation.input.ComposerEditClock
import com.helix.provider.api.ProbeOutcome
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** UI acceptance for receipt ownership, recreation and attachment-preserving manual message edits. */
@Suppress("LongMethod") // Each test preserves one full activity/service ownership journey.
class ConversationReceiptRaceDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun acceptedAttachmentDraftIsAcknowledgedBeforePageRestoreCanRestageIt() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            val session =
                java.util.UUID
                    .randomUUID()
                    .toString()
            storage.sessions.create(session, "Accepted attachment restore", null, null, System.currentTimeMillis())
            try {
                chat.openSession(session)
                awaitEditableComposer()
                chat.stageAttachment(attachmentUri())
                compose.waitUntil(WAIT_MILLIS) { chat.currentStagedAttachmentIds(session).size == 1 }
                val attachmentId = chat.currentStagedAttachmentIds(session).single()
                val artifact = storage.artifacts.resolve(attachmentId)
                compose.onNodeWithTag("chat-new-session").performClick()
                compose.waitUntil(WAIT_MILLIS) {
                    chat.screen.value.isDraft && chat.screen.value.openSessionId != session &&
                        compose.onAllNodesWithTag("chat-pending-remove-$attachmentId").fetchSemanticsNodes().isEmpty()
                }
                val cached = chat.loadComposerDraft(session)
                cached?.let { stale ->
                    storage.composerDrafts.clear(session, stale.revision, stale.clientRequestId)
                }
                val request =
                    ChatSubmission(
                        session,
                        ComposerEditClock
                            .next(cached?.revision ?: 0),
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                        ACCEPTED_ATTACHMENT_TEXT,
                        listOf(attachmentId),
                    )
                assertTrue(chat.saveComposerDraft(request))
                val binding =
                    MessageAttachmentRepository.Binding(attachmentId, "REFERENCE", artifact.sha256)
                val acceptedBody =
                    com.helix.feature.conversation.application.attachment.AttachmentContext.buildUserMessageContent(
                        request.text,
                        listOf(
                            com.helix.feature.conversation.application.attachment.AttachmentContextBlock.Text(
                                "honest.txt",
                                com.helix.core.model.TextAttachmentKind.TXT,
                                artifact.sha256,
                                6,
                                com.helix.core.workspace.FileScopePath(
                                    "app",
                                    "input/attachments/$attachmentId/honest.txt",
                                ),
                            ),
                        ),
                    )
                val coordinator =
                    startFixtureTurn(
                        storage,
                        SystemClock(),
                        {
                            java.util.UUID
                                .randomUUID()
                                .toString()
                        },
                        TurnStartSpec(
                            session,
                            "accepted-attachment-turn",
                            "accepted-attachment-model-call",
                            "fixture",
                            acceptedBody,
                            attachments = listOf(binding),
                            clientRequestId = request.clientRequestId,
                            inputFingerprint =
                                TurnInputFingerprint.of(
                                    acceptedBody,
                                    listOf(
                                        com.helix.core.agent.turn.TurnInputAttachment(
                                            binding.artifactId,
                                            binding.purpose,
                                            binding.boundSha256,
                                        ),
                                    ),
                                ),
                        ),
                    )
                kotlinx.coroutines.runBlocking { coordinator.beginModelStream() }
                coordinator.settleFixtureTerminal(ModelStreamTerminal(TurnState.COMPLETED, null))
                assertNotNull(chat.acceptedComposerReceipt(request))
                assertTrue(chat.currentStagedAttachmentIds(session).isEmpty())

                chat.openSession(session)
                compose.waitUntil(WAIT_MILLIS) {
                    storage.composerDrafts.get(session) == null && composerText().isEmpty()
                }
                assertTrue(chat.currentStagedAttachmentIds(session).isEmpty())
                assertTrue(
                    chat.screen.value.pendingAttachments
                        .isEmpty(),
                )
                assertEquals(1, storage.turns.listBySession(session).size)
            } finally {
                chat.closeSession()
                storage.sessions.archive(session, System.currentTimeMillis())
            }
        }

    @Test
    fun recreationWhileAttachmentConfirmationWaitsForAdmissionRecoversTheAcceptedReceipt() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                server.forceTextResponses = true
                val providerId = createProvider(server.port)
                val session = chat.createSession("Receipt recreation", providerId, "fixture-model-a")
                val admission = submissionGate(chat)
                var admissionHeld = false
                try {
                    chat.openSession(session)
                    awaitEditableComposer()
                    chat.stageAttachment(attachmentUri())
                    compose.waitUntil(WAIT_MILLIS) { chat.currentStagedAttachmentIds(session).size == 1 }
                    compose.onNodeWithTag("chat-input").performTextInput(RECREATED_TEXT)
                    compose.onNodeWithTag("chat-send").performClick()
                    compose.waitUntil(WAIT_MILLIS) { chat.screen.value.pendingDisclosure != null }
                    compose.waitUntil(WAIT_MILLIS) {
                        storage.composerDrafts.get(session)?.text == RECREATED_TEXT
                    }
                    val pending = requireNotNull(chat.pendingComposerSubmission())
                    admission.lock()
                    admissionHeld = true
                    compose.onNodeWithTag("egress-confirm").performClick()
                    assertTrue(storage.turns.listBySession(session).isEmpty())

                    compose.runOnUiThread { compose.activity.recreate() }
                    compose.waitForIdle()
                    compose.waitUntil(WAIT_MILLIS) {
                        compose
                            .onAllNodesWithTag("chat-input")
                            .fetchSemanticsNodes()
                            .singleOrNull()
                            ?.config
                            ?.getOrNull(SemanticsProperties.EditableText)
                            ?.text == RECREATED_TEXT
                    }
                    assertTrue(storage.turns.listBySession(session).isEmpty())
                    val recoveryProbe =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            chat.acceptedComposerReceipt(pending)
                        }
                    assertTrue(!recoveryProbe.isCompleted)
                    recoveryProbe.cancelAndJoin()

                    admission.unlock()
                    admissionHeld = false
                    compose.waitUntil(WAIT_MILLIS) {
                        storage.turns.listBySession(session).size == 1 &&
                            storage.messages.listBySession(session).count { it.role == "USER" } == 1
                    }
                    try {
                        compose.waitUntil(WAIT_MILLIS) {
                            storage.composerDrafts.get(session) == null && composerText().isEmpty()
                        }
                    } catch (failure: ComposeTimeoutException) {
                        val screen = chat.screen.value
                        throw AssertionError(
                            "recreated accepted receipt did not clear composer: " +
                                "draft=${storage.composerDrafts.get(session)}, " +
                                "blocked=${screen.blockedReason}, isSending=${screen.isSending}, " +
                                "pendingDisclosure=${screen.pendingDisclosure != null}, " +
                                "pendingAttachments=${screen.pendingAttachments.map { it.id }}, " +
                                "staged=${chat.currentStagedAttachmentIds(session)}, " +
                                "composer=${composerText()}",
                            failure,
                        )
                    }
                    assertEquals(1, storage.turns.listBySession(session).size)
                    assertTrue(composerText().isEmpty())
                } finally {
                    if (admissionHeld) admission.unlock()
                    storage.turns.listBySession(session).forEach { chat.stopTurn(it.id) }
                    compose.waitUntil(WAIT_MILLIS) { !chat.screen.value.isSending }
                    chat.closeSession()
                    storage.sessions.archive(session, System.currentTimeMillis())
                    container.providerService.delete(providerId)
                }
            }
        }

    @Test
    fun attachmentMessageEditPreservesBindingsAndDoesNotSendAgain() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            LoopbackModelServer(LoopbackModelServer.Mode.OPENAI_LISTED).use { server ->
                server.start()
                server.forceTextResponses = true
                val providerId = createProvider(server.port)
                val session = chat.createSession("Revision receipt owner", providerId, "fixture-model-a")
                try {
                    chat.openSession(session)
                    awaitEditableComposer()
                    val fixtureBudgets = com.helix.core.agent.TurnBudgetBounds.DEFAULT
                    chat.setTurnBudgets(fixtureBudgets)
                    compose.waitUntil(WAIT_MILLIS) { chat.runControl.value.budgets == fixtureBudgets }
                    chat.stageAttachment(attachmentUri())
                    compose.waitUntil(WAIT_MILLIS) { chat.currentStagedAttachmentIds(session).size == 1 }

                    compose.onNodeWithTag("chat-input").performTextInput(ORIGINAL_TEXT)
                    compose.onNodeWithTag("chat-send").performClick()
                    compose.waitUntil(WAIT_MILLIS) { chat.screen.value.pendingDisclosure != null }
                    compose.onNodeWithTag("egress-confirm").performClick()
                    try {
                        compose.waitUntil(WAIT_MILLIS) {
                            storage.turns.listBySession(session).size == 1 &&
                                storage.turns.listBySession(session).all { it.state == TurnState.COMPLETED.name }
                        }
                    } catch (failure: ComposeTimeoutException) {
                        throw AssertionError(
                            "attachment send: turns=${storage.turns.listBySession(
                                session,
                            ).map { it.state to it.errorCode }}, " +
                                "blocked=${chat.screen.value.blockedReason}",
                            failure,
                        )
                    }
                    try {
                        compose.waitUntil(WAIT_MILLIS) {
                            storage.composerDrafts.get(session) == null &&
                                !chat.screen.value.isSending &&
                                chat.currentStagedAttachmentIds(session).isEmpty() &&
                                composerText().isEmpty()
                        }
                    } catch (failure: ComposeTimeoutException) {
                        val screen = chat.screen.value
                        throw AssertionError(
                            "ordinary attachment send did not settle: " +
                                "draft=${storage.composerDrafts.get(session)}, " +
                                "blocked=${screen.blockedReason}, isSending=${screen.isSending}, " +
                                "pendingDisclosure=${screen.pendingDisclosure != null}, " +
                                "pendingAttachments=${screen.pendingAttachments.map { it.id }}, " +
                                "staged=${chat.currentStagedAttachmentIds(session)}, " +
                                "composer=${composerText()}",
                            failure,
                        )
                    }

                    val target = requireNotNull(storage.messages.latestUser(session)).id
                    compose
                        .onNodeWithTag(
                            "chat-timeline",
                        ).performScrollToNode(hasTestTag("chat-message-toggle-$target"))
                    compose.onNodeWithTag("chat-message-toggle-$target").performClick()
                    compose
                        .onNodeWithTag("chat-edit-$target")
                        .performScrollTo()
                        .assertIsEnabled()
                        .performClick()
                    awaitMessageEditor()
                    compose.onNodeWithTag("message-edit-input").assertIsEnabled()
                    val bindings = storage.messageAttachments.listByMessage(target)
                    val before = storage.messages.resolve(target)
                    compose.onNodeWithTag("message-edit-input").performTextReplacement(REVISED_TEXT)
                    compose.onNodeWithTag("message-edit-save").performClick()
                    compose.waitUntil(WAIT_MILLIS) {
                        compose.onAllNodesWithTag("message-edit-input").fetchSemanticsNodes().isEmpty()
                    }
                    val edited = storage.messages.resolve(target)
                    assertEquals(before.turnId, edited.turnId)
                    assertEquals(before.supersededBy, edited.supersededBy)
                    assertEquals(bindings, storage.messageAttachments.listByMessage(target))
                    assertTrue(storage.messages.readContent(edited)?.contains(REVISED_TEXT) == true)
                    assertEquals(1, storage.turns.listBySession(session).size)
                    assertNull(chat.screen.value.pendingDisclosure)
                    assertNull(chat.loadComposerDraft(session))
                } finally {
                    storage.turns.listBySession(session).forEach { chat.stopTurn(it.id) }
                    compose.waitUntil(WAIT_MILLIS) { !chat.screen.value.isSending }
                    chat.closeSession()
                    storage.sessions.archive(session, System.currentTimeMillis())
                    container.providerService.delete(providerId)
                }
            }
        }

    @Test
    fun cancelledMessageEditPreservesOriginalAndOrdinaryComposer() =
        runBlocking {
            compose.resetDeterministicUiState()
            val container = compose.container()
            val chat = container.chatService
            val storage = container.storage
            val session =
                java.util.UUID
                    .randomUUID()
                    .toString()
            val message =
                java.util.UUID
                    .randomUUID()
                    .toString()
            storage.sessions.create(session, "Cancel edit", null, null, System.currentTimeMillis())
            storage.messages.append(message, session, null, "USER", "TEXT", "before edit")
            try {
                chat.openSession(session)
                awaitEditableComposer()
                compose.onNodeWithTag("chat-input").performTextInput("ordinary draft")
                compose.onNodeWithTag("chat-message-toggle-$message").performScrollTo().performClick()
                compose
                    .onNodeWithTag("chat-edit-$message")
                    .performScrollTo()
                    .assertIsEnabled()
                    .performClick()
                awaitMessageEditor()
                compose.onNodeWithTag("message-edit-input").assertIsEnabled().performTextReplacement("discard edit")
                compose.onNodeWithTag("message-edit-cancel").performClick()
                compose.onNodeWithTag("message-edit-input").assertDoesNotExist()
                assertEquals("before edit", storage.messages.readContent(storage.messages.resolve(message)))
                assertEquals("ordinary draft", composerText())
                compose.onNodeWithTag("chat-edit-$message").performScrollTo().performClick()
                awaitMessageEditor()
                val reopened =
                    compose
                        .onNodeWithTag("message-edit-input")
                        .assertIsEnabled()
                        .fetchSemanticsNode()
                        .config
                        .getOrNull(SemanticsProperties.EditableText)
                        ?.text
                assertEquals("before edit", reopened)
                compose.onNodeWithTag("message-edit-cancel").performClick()
                assertEquals(0, storage.turns.listBySession(session).size)
            } finally {
                chat.closeSession()
                storage.sessions.archive(session, System.currentTimeMillis())
            }
        }

    private fun awaitMessageEditor() {
        compose.waitUntil(WAIT_MILLIS) {
            compose
                .onAllNodesWithTag("message-edit-input")
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.let { it.config.getOrNull(SemanticsProperties.Disabled) == null } == true
        }
    }

    private fun awaitEditableComposer() {
        compose.waitUntil(WAIT_MILLIS) {
            compose
                .onAllNodesWithTag("chat-input")
                .fetchSemanticsNodes()
                .singleOrNull()
                ?.let { it.config.getOrNull(SemanticsProperties.Disabled) == null } == true
        }
    }

    private fun composerText(): String =
        compose
            .onAllNodesWithTag("chat-input")
            .fetchSemanticsNodes()
            .singleOrNull()
            ?.config
            ?.getOrNull(SemanticsProperties.EditableText)
            ?.text
            .orEmpty()

    private fun submissionGate(chat: ChatService): Mutex {
        val field = ChatService::class.java.getDeclaredField("submissionGate").apply { isAccessible = true }
        return field.get(chat) as Mutex
    }

    private fun attachmentUri(): String =
        Uri
            .parse(
                "content://${TransferTestDocumentsProvider.authority()}/document/honest",
            ).toString()

    private suspend fun createProvider(port: Int): String {
        val service = compose.container().providerService
        val id =
            service.create(
                ProviderDraft(
                    null,
                    "Receipt race fixture",
                    ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                    NormalizedEndpoint.parse("http://127.0.0.1:$port/v1"),
                    "fixture-model-a",
                    "{}",
                    false,
                ),
                null,
            )
        try {
            check(service.runConnectionTest(id) is ProbeOutcome.Ok)
        } catch (failure: Throwable) {
            service.delete(id)
            throw failure
        }
        return id
    }

    private companion object {
        const val WAIT_MILLIS = 20_000L
        const val RECREATED_TEXT = "RECOVER-AFTER-RECREATE"
        const val ORIGINAL_TEXT = "ORIGINAL-WITH-ATTACHMENT"
        const val REVISED_TEXT = "REVISED-WITH-ATTACHMENT"
        const val ACCEPTED_ATTACHMENT_TEXT = "ACCEPTED-ATTACHMENT-BEFORE-PAGE-RESTORE"
    }
}
