package com.helix.core.agent

import com.helix.core.agent.ModelRecoveryPolicy
import com.helix.core.agent.ModelStreamState
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelRecoveryPolicyTest {
    @Test fun evenAnEmptyToolStartPreventsReplay() {
        val stream = ModelStreamState()
        stream.apply(
            ModelEvent.ToolCallStarted(
                0,
                com.helix.core.model
                    .ToolCallId("call"),
                "read",
            ),
        )
        stream.apply(ModelEvent.Error(ModelErrorCode.TRANSPORT, retryable = true))
        assertNull(ModelRecoveryPolicy.retryDelayMillis(true, 0, stream))
    }

    @Test fun partialTextIsNotSilentlyReplayed() {
        val stream = ModelStreamState()
        stream.apply(ModelEvent.TextDelta("partial"))
        stream.apply(ModelEvent.Error(ModelErrorCode.TRANSPORT, retryable = true))
        assertNull(ModelRecoveryPolicy.retryDelayMillis(true, 0, stream))
    }

    @Test fun transientEmptyInferenceHasBoundedBackoff() {
        val stream = ModelStreamState()
        stream.apply(ModelEvent.Error(ModelErrorCode.TRANSPORT, retryable = true))
        assertEquals(1_000L, ModelRecoveryPolicy.retryDelayMillis(true, 0, stream))
        assertEquals(2_000L, ModelRecoveryPolicy.retryDelayMillis(true, 1, stream))
        assertNull(ModelRecoveryPolicy.retryDelayMillis(true, 2, stream))
        assertNull(ModelRecoveryPolicy.retryDelayMillis(false, 0, stream))
    }

    @Test fun permanentFailureDoesNotRetry() {
        val stream = ModelStreamState()
        stream.apply(ModelEvent.Error(ModelErrorCode.TRANSPORT, retryable = false))
        assertNull(ModelRecoveryPolicy.retryDelayMillis(true, 0, stream))
    }
}
