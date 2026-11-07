package com.helix.app.application.agent

import com.helix.core.agent.RecoveryInspectionIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubmissionAdmissionFactsTest {
    private val session = SubmissionSession("session", "provider", "model", null)
    private val binding = TurnSessionAdmissionBinding("provider", "model")

    @Test fun preparedBindingRejectsArchiveAndProviderOrModelChanges() {
        assertTrue(binding.matches(session))
        assertFalse(binding.matches(session.copy(archivedAt = 1L)))
        assertFalse(binding.matches(session.copy(providerId = "other")))
        assertFalse(binding.matches(session.copy(modelId = "other")))
        assertFalse(binding.matches(session.copy(modelId = null)))
    }

    @Test fun unsetModelIsPreservedRatherThanReplacedByProviderDefault() {
        val unset = TurnSessionAdmissionBinding("provider", null)
        assertTrue(unset.matches(session.copy(modelId = null)))
        assertFalse(unset.matches(session))
    }

    @Test fun inspectionReceiptRemainsBoundToOriginalTurn() {
        assertEquals("auto-recovery:original", RecoveryInspectionIdentity.requestId("original"))
    }
}
