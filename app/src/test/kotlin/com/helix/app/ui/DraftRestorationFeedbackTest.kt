package com.helix.app.ui

import com.helix.app.chat.ChatSubmission
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftRestorationFeedbackTest {
    @Test fun attachmentLoadingDoesNotBlockTypingButWaitsBeforeSending() =
        runBlocking {
            val buffer = ConversationDraftBuffer("session")
            buffer.initialize { null }
            assertTrue(buffer.editable)
            assertFalse(buffer.canSubmit)
            buffer.edit("Keep typing")
            buffer.restoredAttachments(emptyList())
            assertTrue(buffer.canSubmit)
            assertEquals("Keep typing", buffer.captureSubmission().text)
        }

    @Test fun recoveryFailureWithoutAttachmentsDoesNotStrandPlainText() =
        runBlocking {
            val buffer = ConversationDraftBuffer("session")
            buffer.initialize { null }
            buffer.edit("Preserved")
            buffer.attachmentRestoreFailed()
            assertTrue(buffer.failed)
            assertTrue(buffer.editable)
            assertTrue(buffer.canSubmit)
            assertEquals("Preserved", buffer.value.text)
        }

    @Test fun unresolvedAttachmentsRemainExplicitUntilTheUserRemovesThem() =
        runBlocking {
            val buffer = ConversationDraftBuffer("session")
            buffer.initialize { ChatSubmission("session", 0, "request", "Preserved", listOf("attachment")) }
            buffer.attachmentRestoreFailed()
            assertEquals(listOf("attachment"), buffer.missingAttachments)
            assertEquals(listOf("attachment"), buffer.value.attachmentIds)
            assertFalse(buffer.canSubmit)
            buffer.discardMissingAttachments()
            assertTrue(buffer.canSubmit)
            assertEquals("Preserved", buffer.value.text)
        }

    @Test fun laterStagingFailureDoesNotInvalidatePreviouslyRestoredAttachments() =
        runBlocking {
            val buffer = ConversationDraftBuffer("session")
            buffer.initialize { null }
            buffer.restoredAttachments(emptyList())
            buffer.attachments(listOf("ready"))
            buffer.attachmentRestoreFailed()
            assertEquals(listOf("ready"), buffer.value.attachmentIds)
            assertTrue(buffer.missingAttachments.isEmpty())
            assertTrue(buffer.canSubmit)
        }
}
