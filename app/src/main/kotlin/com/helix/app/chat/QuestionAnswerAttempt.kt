package com.helix.app.chat

/** Only undelivered terminal attempts may be retried. Accepted or parked answers keep their identity. */
internal object QuestionAnswerAttempt {
    fun next(
        questionId: String,
        previousState: String?,
        previousSequence: Long?,
    ): String? =
        when (previousState) {
            null -> "answer:$questionId"
            "WITHDRAWN", "FAILED" -> "answer:$questionId:retry:${requireNotNull(previousSequence)}"
            else -> null
        }
}
