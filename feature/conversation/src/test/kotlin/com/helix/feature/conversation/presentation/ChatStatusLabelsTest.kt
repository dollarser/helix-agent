package com.helix.feature.conversation.presentation

import com.helix.core.model.TurnState
import com.helix.feature.conversation.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatStatusLabelsTest {
    private val labels = ChatStatusLabels { resource, _ -> resource.toString() }

    @Test fun failedSummaryUsesCompactionSpecificOutputFeedback() {
        assertEquals(
            R.string.context_summary_output_limit.toString(),
            labels.terminalLabel(TurnState.FAILED, "CONTEXT_SUMMARY_OUTPUT_LIMIT"),
        )
        assertEquals(R.string.budget_output_limit, labels.modelTerminalCodeRes("OUTPUT_TOKEN_LIMIT"))
    }

    @Test fun unusableSummaryAndNoGainRemainVisibleAsDistinctFailures() {
        assertEquals(
            R.string.context_summary_invalid.toString(),
            labels.terminalLabel(TurnState.FAILED, "CONTEXT_SUMMARY_INVALID"),
        )
        assertEquals(
            R.string.context_summary_no_gain.toString(),
            labels.terminalLabel(TurnState.FAILED, "CONTEXT_NO_GAIN"),
        )
        assertEquals(R.string.context_not_compactable, labels.modelTerminalCodeRes("CONTEXT_NOT_COMPACTABLE"))
    }

    @Test fun cancelledOrCompletedCompactionDoesNotShowFailureFeedback() {
        assertEquals(
            R.string.turn_stopped.toString(),
            labels.terminalLabel(TurnState.CANCELLED, "CONTEXT_SUMMARY_OUTPUT_LIMIT"),
        )
        assertNull(labels.terminalLabel(TurnState.COMPLETED, null))
    }
}
