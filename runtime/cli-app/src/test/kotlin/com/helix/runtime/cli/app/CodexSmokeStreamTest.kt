package com.helix.runtime.cli.app

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CodexSmokeStreamTest {
    private val delta = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"HELIX_OK\"}\n\n"
    private val completed = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n"

    @Test fun completedResponsePasses() {
        assertEquals("HELIX_OK", CodexSmokeStream.read(Buffer().writeUtf8(delta + completed)))
    }

    @Test fun matchingTextWithoutCompletionFails() {
        assertEquals("response-incomplete", failure(delta).stage)
    }

    @Test fun doneMarkerDoesNotProveCompletion() {
        assertEquals("response-incomplete", failure(delta + "data: [DONE]\n\n").stage)
    }

    @Test fun failedTerminalDoesNotPassWithMatchingText() {
        assertEquals("response-terminal", failure(delta + "data: {\"type\":\"response.failed\"}\n\n").stage)
    }

    @Test fun completedEventWithIncompleteStatusFails() {
        val event = "data: {\"type\":\"response.completed\",\"response\":{\"status\":\"incomplete\"}}\n\n"
        assertEquals("response-terminal", failure(delta + event).stage)
    }

    @Test fun oversizedLineIsRejectedBeforeItIsConsumed() {
        val source = Buffer().writeUtf8("x".repeat((CodexSubscriptionSmoke.MAX_STREAM_BYTES * 2).toInt()))
        val original = source.size
        assertEquals(
            "response-too-large",
            assertThrows(CodexSmokeException::class.java) {
                CodexSmokeStream.read(source)
            }.stage,
        )
        org.junit.Assert.assertTrue(source.size >= original - CodexSubscriptionSmoke.MAX_STREAM_BYTES - 1)
    }

    @Test fun exactStreamByteLimitPasses() {
        val events = delta + completed
        val body = events + ":" + " ".repeat(CodexSubscriptionSmoke.MAX_STREAM_BYTES.toInt() - events.length - 1)
        assertEquals("HELIX_OK", CodexSmokeStream.read(Buffer().writeUtf8(body)))
    }

    @Test fun malformedEventFailsWithProtocolStage() {
        assertEquals("response-protocol", failure("data: [1,2,3]\n\n").stage)
        assertEquals("response-protocol", failure("data: {broken\n\n").stage)
    }

    @Test fun outputBeyondTextLimitFails() {
        val event = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"" + "x".repeat(65) + "\"}\n\n"
        assertEquals("output-too-large", failure(event + completed).stage)
    }

    private fun failure(body: String): CodexSmokeException =
        assertThrows(CodexSmokeException::class.java) { CodexSmokeStream.read(Buffer().writeUtf8(body)) }
}
