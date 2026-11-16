package com.helix.app.eval

import com.helix.app.provider.BuiltInModel
import com.helix.feature.conversation.input.ChatSubmission
import com.helix.feature.conversation.input.ChatSubmissionOutcome
import com.helix.feature.conversation.input.ChatSubmissionReceipt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticModelSubmissionDeviceTest {
    private val request = ChatSubmission("owned", 0, "original-request", "Synthetic text")

    @Test
    fun disclosureScopeFailsClosed() {
        fun allowed(
            open: String? = "owned",
            input: ChatSubmission = request,
            provider: String = BuiltInModel.PROVIDER_ID,
            origin: String = "https://api.hilex.me",
            attachments: Int = 0,
            sensitive: Boolean = false,
        ) = syntheticDisclosureAllowed("owned", open, input, provider, origin, attachments, sensitive)
        assertTrue(allowed())
        assertFalse(allowed(open = "other"))
        assertFalse(allowed(input = request.copy(sessionId = "other")))
        assertFalse(allowed(input = request.copy(attachmentIds = listOf("foreign"))))
        assertFalse(allowed(provider = "other"))
        assertFalse(allowed(attachments = 1))
        assertFalse(allowed(sensitive = true))
        for (origin in listOf(
            "http://api.hilex.me",
            "https://api.hilex.me.attacker.test",
            "https://other.test",
            "https://user@api.hilex.me",
            "https://api.hilex.me:444",
            "https://api.hilex.me/path",
            "https://api.hilex.me?data=x",
            "https://api.hilex.me#fragment",
            "not a URI",
        )) {
            assertFalse(origin, allowed(origin = origin))
        }
    }

    @Test
    fun confirmationPreservesOriginalIdentityAndRecordsBothReceipts() =
        runBlocking {
            val outcomes = mutableListOf<ChatSubmissionOutcome>()
            var confirmations = 0
            val receipt =
                admitSyntheticSubmission(
                    request,
                    send = {
                        assertSame(request, it)
                        ChatSubmissionReceipt(it, ChatSubmissionOutcome.PendingConfirmation)
                    },
                    confirm = {
                        assertSame(request, it)
                        confirmations++
                        ChatSubmissionReceipt(it, ChatSubmissionOutcome.Accepted("turn"))
                    },
                    record = { outcomes += it.outcome },
                )
            assertEquals(1, confirmations)
            assertEquals(2, outcomes.size)
            assertTrue(receipt.outcome is ChatSubmissionOutcome.Accepted)
        }

    @Test
    fun rejectedOrEnqueuedSubmissionIsNeverConfirmedOrRetried() =
        runBlocking {
            val outcomes = listOf(ChatSubmissionOutcome.Rejected("BLOCKED"), ChatSubmissionOutcome.Enqueued("input"))
            for (outcome in outcomes) {
                var sends = 0
                val receipt =
                    admitSyntheticSubmission(
                        request,
                        send = {
                            sends++
                            ChatSubmissionReceipt(it, outcome)
                        },
                        confirm = { error("Must not confirm") },
                        record = {},
                    )
                assertEquals(outcome, receipt.outcome)
                assertEquals(1, sends)
            }
        }

    @Test
    fun confirmationRejectionIsNotResubmitted() =
        runBlocking {
            var confirms = 0
            val receipt =
                admitSyntheticSubmission(
                    request,
                    send = { ChatSubmissionReceipt(it, ChatSubmissionOutcome.PendingConfirmation) },
                    confirm = {
                        confirms++
                        ChatSubmissionReceipt(it, ChatSubmissionOutcome.Rejected("CONFIRMATION_CHANGED"))
                    },
                    record = {},
                )
            assertEquals(1, confirms)
            assertTrue(receipt.outcome is ChatSubmissionOutcome.Rejected)
        }

    @Test
    fun changedReceiptIdentityFailsBeforeConfirmation() =
        runBlocking {
            var rejected = false
            try {
                admitSyntheticSubmission(
                    request,
                    send = {
                        ChatSubmissionReceipt(
                            it.copy(clientRequestId = "changed"),
                            ChatSubmissionOutcome.PendingConfirmation,
                        )
                    },
                    confirm = { error("Must not confirm a different request") },
                    record = {},
                )
            } catch (_: IllegalStateException) {
                rejected = true
            }
            assertTrue(rejected)
        }
}
