package com.helix.app.infrastructure.chat

import com.helix.core.model.input.InputConfiguration
import com.helix.core.model.input.KnowledgeReference
import com.helix.core.model.input.SessionInputDelivery
import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputState
import com.helix.feature.conversation.input.PendingInputView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingInputViewMapperTest {
    @Test fun pendingProjectionRetainsDisplayedIdentityRevisionDeliveryAndReferences() {
        val reference = KnowledgeReference("NOTE", "note", "Referenced note", "a".repeat(64), 20)
        val record = input(SessionInputState.PENDING).copy(knowledgeReferences = listOf(reference))

        assertEquals(
            PendingInputView("queued", 7, SessionInputDelivery.QUEUE, false, listOf(reference)),
            record.toPendingInputView(),
        )
    }

    @Test fun attentionProjectionPreservesDeliveryWithoutBecomingAnOrdinaryPendingInput() {
        SessionInputDelivery.entries.forEach { delivery ->
            val record = input(SessionInputState.NEEDS_ATTENTION).copy(delivery = delivery)
            val projected = requireNotNull(record.toPendingInputView())

            assertTrue(projected.needsAttention)
            assertTrue(projected.canSend)
            assertEquals(delivery, projected.delivery)
        }
    }

    @Test fun deliveredWithdrawnAndFailedInputsDoNotOccupyTheComposer() {
        listOf(SessionInputState.APPENDED, SessionInputState.WITHDRAWN, SessionInputState.FAILED).forEach { state ->
            assertNull(input(state).toPendingInputView())
        }
    }

    @Test fun filteringPreservesRepositoryOrderAndDisplayedRevisions() {
        val first = input(SessionInputState.PENDING).copy(inputId = "first", sequence = 2)
        val second = input(SessionInputState.NEEDS_ATTENTION).copy(inputId = "second", sequence = 3, revision = 9)
        val source = listOf(first, input(SessionInputState.APPENDED), second)
        val projected = source.mapNotNull(SessionInputRecord::toPendingInputView)

        assertEquals(listOf("first", "second"), projected.map { it.inputId })
        assertEquals(listOf(7L, 9L), projected.map { it.revision })
        assertFalse(projected.first().needsAttention)
        assertTrue(projected.last().needsAttention)
    }

    @Test fun newerStorageRevisionDoesNotRetargetAnAlreadyDisplayedAction() {
        val original = input(SessionInputState.PENDING)
        val displayed = requireNotNull(original.toPendingInputView())
        val newer =
            requireNotNull(original.copy(revision = 8, state = SessionInputState.NEEDS_ATTENTION).toPendingInputView())

        assertEquals(7L, displayed.revision)
        assertFalse(displayed.needsAttention)
        assertEquals(8L, newer.revision)
        assertTrue(newer.needsAttention)
    }

    private fun input(state: SessionInputState) =
        SessionInputRecord(
            schemaVersion = 1,
            inputId = "queued",
            sessionId = "session",
            sequence = 1,
            delivery = SessionInputDelivery.QUEUE,
            expectedTurnId = null,
            revision = 7,
            textRef = "fixture",
            textBytes = 10,
            attachments = emptyList(),
            configuration = InputConfiguration("provider", "model", "ACT", "fixture"),
            state = state,
            consumedTurnId = null,
            messageId = null,
            requestModelCallId = null,
            blockedReason = null,
            createdAt = 0,
            updatedAt = 0,
        )
}
