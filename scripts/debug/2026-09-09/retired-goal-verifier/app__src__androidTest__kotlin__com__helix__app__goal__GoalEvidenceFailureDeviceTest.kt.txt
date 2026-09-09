package com.helix.app.goal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.TurnState
import com.helix.core.storage.content.ContentRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoalEvidenceFailureDeviceTest {
    @Test fun unfinishedTurnHasStableReason() =
        withGoalEvidenceStorage { storage ->
            seedGoalToolEvidence(storage, terminal = TurnState.CANCELLED)
            val rejected =
                assertThrows(GoalEvidenceRejected::class.java) { goalEvidenceReader(storage).read("goal", "call") }
            assertEquals(GoalEvidenceFailure.SOURCE_NOT_READY, rejected.reason)
        }

    @Test fun pendingCallsAndDeletedContentRemainDistinct() =
        withGoalEvidenceStorage { storage ->
            seedGoalToolEvidence(storage)
            val pending = storage.toolCalls.append("pending", "turn", "pending", "time.now", "1", "{}", "PENDING")
            val reader = goalEvidenceReader(storage)
            val unsettled = assertThrows(GoalEvidenceRejected::class.java) { reader.read("goal", "call") }
            assertEquals(GoalEvidenceFailure.UNSETTLED_CALLS, unsettled.reason)
            storage.toolCalls.updateState(pending, com.helix.core.model.ToolCallState.CANCELLED)
            val result = requireNotNull(storage.toolResults.byToolCall("call"))
            storage.contentStore.delete(ContentRef.parse(requireNotNull(result.contentRef)))
            val changed = assertThrows(GoalEvidenceRejected::class.java) { reader.read("goal", "call") }
            assertEquals(GoalEvidenceFailure.CONTENT_CHANGED, changed.reason)
        }
}
