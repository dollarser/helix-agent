package com.helix.app.chat

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.helix.app.MainActivity
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.ui.container
import com.helix.app.ui.resetDeterministicUiState
import com.helix.core.model.Clock
import com.helix.core.model.TurnState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Host runner edits a normal composer, SIGKILLs MainActivity's PID, and then verifies here. */
class ComposerProcessRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun seedComposerRecovery() =
        runBlocking {
            compose.resetDeterministicUiState()
            val storage = compose.container().storage
            storage.sessions.create(COMPOSER_SESSION, "COMPOSER-RECOVERY", null, null, 1)
            assertTrue(
                storage.composerDrafts.save(
                    ChatSubmission(
                        COMPOSER_SESSION,
                        0,
                        "pending-composer-214",
                        "RECOVER-DRAFT-214",
                    ).toDraftEntity(),
                    null,
                ),
            )

            storage.sessions.create(ACCEPTED_SESSION, "COMPOSER-ACCEPTED", null, null, 2)
            val accepted =
                ChatSubmission(
                    ACCEPTED_SESSION,
                    0,
                    ACCEPTED_REQUEST,
                    ACCEPTED_TEXT,
                )
            assertTrue(storage.composerDrafts.save(accepted.toDraftEntity(), null))
            val clock =
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(10)
                }
            val coordinator =
                TurnCoordinator.start(
                    storage,
                    clock,
                    { UUID.randomUUID().toString() },
                    TurnStartSpec(
                        ACCEPTED_SESSION,
                        "composer-accepted-turn",
                        "composer-accepted-model-call",
                        "fixture",
                        accepted.text,
                        clientRequestId = accepted.clientRequestId,
                        inputFingerprint = TurnInputFingerprint.of(accepted.text, emptyList()),
                    ),
                )
            coordinator.beginModelStream()
            coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            compose.container().chatService.closeSession()
        }

    @Test fun verifyComposerRecovery() =
        runBlocking {
            val container = compose.container()
            val storage = container.storage
            val draft = container.chatService.loadComposerDraft(COMPOSER_SESSION)
            assertNotNull(draft)
            assertEquals("NORMAL-PROCESS-DRAFT-214", draft!!.text)
            assertTrue(draft.revision > 0)
            assertNull(draft.revisedMessageId)
            assertTrue(storage.messages.listBySession(COMPOSER_SESSION).isEmpty())
            assertTrue(storage.turns.listBySession(COMPOSER_SESSION).isEmpty())

            assertNull(container.chatService.loadComposerDraft(ACCEPTED_SESSION))
            val turns = storage.turns.listBySession(ACCEPTED_SESSION)
            assertEquals(1, turns.size)
            assertEquals("composer-accepted-turn", turns.single().id)
            val messages = storage.messages.listBySession(ACCEPTED_SESSION)
            assertEquals(1, messages.size)
            assertEquals(ACCEPTED_TEXT, storage.messages.readContent(messages.single()))
            val accepted =
                ChatSubmission(
                    ACCEPTED_SESSION,
                    0,
                    ACCEPTED_REQUEST,
                    ACCEPTED_TEXT,
                )
            val receipt = container.chatService.acceptedComposerReceipt(accepted)
            assertNotNull(receipt)
            assertEquals(
                "composer-accepted-turn",
                (receipt!!.outcome as ChatSubmissionOutcome.Accepted).turnId,
            )
        }

    private companion object {
        const val COMPOSER_SESSION = "composer-recovery"
        const val ACCEPTED_SESSION = "composer-accepted"
        const val ACCEPTED_REQUEST = "accepted-composer-214"
        const val ACCEPTED_TEXT = "ACCEPTED-COMPOSER-214"
    }
}
