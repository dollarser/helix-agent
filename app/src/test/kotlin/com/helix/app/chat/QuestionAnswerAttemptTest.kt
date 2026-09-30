package com.helix.app.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class QuestionAnswerAttemptTest {
    @Test fun onlyUndeliveredTerminalAnswersCanStartANewAttempt() {
        assertEquals("answer:q", QuestionAnswerAttempt.next("q", null, null))
        listOf("PENDING", "NEEDS_ATTENTION", "APPENDED", "unknown").forEach {
            assertNull(QuestionAnswerAttempt.next("q", it, 1))
        }
        val retry = QuestionAnswerAttempt.next("q", "FAILED", 3)
        assertEquals(retry, QuestionAnswerAttempt.next("q", "FAILED", 3))
        assertEquals("answer:q:retry:3", retry)
        assertNotEquals(retry, QuestionAnswerAttempt.next("q", "WITHDRAWN", 4))
    }

    @Test fun forkedQuestionsAndDismissalsAreInertButOrdinaryMessagesAreUnchanged() {
        assertEquals(UserQuestionService.HISTORY, UserQuestionService.forkKind(UserQuestionService.KIND))
        assertEquals(UserQuestionService.HISTORY, UserQuestionService.forkKind(UserQuestionService.DISMISSED))
        assertEquals("text", UserQuestionService.forkKind("text"))
    }

    @Test fun malformedOptionsAreRejectedBeforeStorageCanBeChanged() {
        listOf(
            """{"question":"Q","options":["same","same"]}""",
            """{"question":"Q","options":{}}""",
            """{"question":123}""",
            """{"question":"Q","options":[123]}""",
            """{"question":"Q","multiple":{}}""",
        ).forEach { raw ->
            assertThrows(IllegalArgumentException::class.java) {
                UserQuestionService.decode("q", "s", Json.parseToJsonElement(raw).jsonObject)
            }
        }
    }
}
