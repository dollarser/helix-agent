package com.helix.app.chat

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import com.helix.app.ui.container
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Await the durable consumption of this exact input; queued admission is not synchronous. */
internal fun AndroidComposeTestRule<*, *>.awaitAdmittedTurn(receipt: ChatSubmissionReceipt): String {
    val outcome = receipt.outcome
    assertTrue(
        "Expected admitted input, got $outcome",
        outcome is ChatSubmissionOutcome.Accepted || outcome is ChatSubmissionOutcome.Enqueued,
    )
    val storage = container().storage
    val inputId = receipt.submission.clientRequestId
    if (outcome is ChatSubmissionOutcome.Enqueued) assertEquals(inputId, outcome.inputId)
    waitUntil(15_000) { storage.sessionInputs.get(inputId)?.consumedTurnId != null }
    val turnId = requireNotNull(storage.sessionInputs.get(inputId)?.consumedTurnId)
    if (outcome is ChatSubmissionOutcome.Accepted) assertEquals(outcome.turnId, turnId)
    assertEquals(receipt.submission.sessionId, storage.turns.resolve(turnId).sessionId)
    return turnId
}
