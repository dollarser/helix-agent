package com.helix.app.ui

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.helix.app.MainActivity
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnCoordinator
import com.helix.app.agent.TurnStartSpec
import com.helix.app.chat.ChatSubmission
import com.helix.app.chat.TurnInputFingerprint
import com.helix.app.chat.toDraftEntity
import com.helix.core.model.Clock
import com.helix.core.model.TurnState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Host runner starts normal MainActivity, edits, SIGKILLs its concrete PID, then verifies here. */
class MessageEditRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun seedRevisionRecovery() =
        runBlocking {
            compose.resetDeterministicUiState()
            val storage = compose.container().storage
            storage.sessions.create("revision-recovery", "REVISION-RECOVERY", null, null, 1)
            storage.messages.append("revision-target", "revision-recovery", null, "USER", "TEXT", "ORIGINAL-RECOVERY")
            storage.composerDrafts.save(
                ChatSubmission(
                    "revision-recovery",
                    0,
                    "pending-revision",
                    "RECOVER-DRAFT-215",
                    revisedMessageId = "revision-target",
                ).toDraftEntity(),
                null,
            )
            storage.sessions.create("revision-accepted", "REVISION-ACCEPTED", null, null, 2)
            storage.messages.append("accepted-target", "revision-accepted", null, "USER", "TEXT", "OLD-ACCEPTED")
            val accepted =
                ChatSubmission(
                    "revision-accepted",
                    0,
                    "accepted-revision",
                    "NEW-ACCEPTED",
                    revisedMessageId = "accepted-target",
                )
            storage.composerDrafts.save(accepted.toDraftEntity(), null)
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
                        "revision-accepted",
                        "revision-turn",
                        "revision-model",
                        "fixture",
                        accepted.text,
                        clientRequestId = accepted.clientRequestId,
                        inputFingerprint = TurnInputFingerprint.of(accepted.text, emptyList(), "accepted-target"),
                        revisedMessageId = "accepted-target",
                    ),
                )
            coordinator.beginModelStream()
            coordinator.terminalize(ModelStreamTerminal(TurnState.COMPLETED, null))
            compose.container().chatService.closeSession()
        }

    @Test fun verifyRevisionRecovery() =
        runBlocking {
            val storage = compose.container().storage
            val draft = compose.container().chatService.loadComposerDraft("revision-recovery")!!
            assertEquals("revision-target", draft.revisedMessageId)
            assertEquals("NORMAL-PROCESS-EDIT-215", draft.text)
            assertTrue(draft.revision > 0)
            val original = storage.messages.resolve("revision-target")
            assertEquals("ORIGINAL-RECOVERY", storage.messages.readContent(original))
            assertTrue(storage.turns.listBySession("revision-recovery").isEmpty())
            assertEquals(1, storage.turns.listBySession("revision-accepted").size)
            val current = storage.messages.latestUser("revision-accepted")!!
            assertEquals("NEW-ACCEPTED", storage.messages.readContent(current))
            assertEquals("accepted-revision", storage.messages.resolve("accepted-target").supersededBy)
        }
}
