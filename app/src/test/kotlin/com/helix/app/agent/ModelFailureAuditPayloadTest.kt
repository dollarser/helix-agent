package com.helix.app.agent

import com.helix.app.infrastructure.agent.durableModelFailurePayload
import com.helix.core.agent.ModelFailureDiagnostics
import com.helix.core.agent.ModelStreamState
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelFailureDiagnostic
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelFailureAuditPayloadTest {
    @Test fun serverTextIsAvailableForExpiringDetailsButNeverCopiedIntoTheLongLivedAudit() {
        val stream = ModelStreamState()
        val diagnostic =
            ModelFailureDiagnostic(
                ModelFailureDiagnostic.Stage.HTTP,
                ModelFailureDiagnostic.Reason.HTTP_STATUS,
                httpStatus = 400,
                serverMessage = "Maximum context length is 32768",
                serverCode = "BadRequestError",
                errorParameter = "max_tokens",
            )
        stream.apply(ModelEvent.Error(ModelErrorCode.PROTOCOL, false, diagnostic))
        val full = ModelFailureDiagnostics.record("PROTOCOL", stream, true, null, 0)
        assertTrue(full.contains("Maximum context length"))
        val durable = durableModelFailurePayload(full)
        assertFalse(durable.contains("serverMessage"))
        assertFalse(durable.contains("Maximum context length"))
        assertTrue(durable.contains("BadRequestError"))
        assertTrue(durable.contains("400"))
    }
}
