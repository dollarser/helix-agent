package com.helix.app.ui

import com.helix.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerFeedbackTest {
    @Test fun blockedDeliveryExplainsTheActualReason() {
        val state = ComposerAvailability(delivery = false, deliveryReason = R.string.composer_restoring_attachments)
        assertFalse(state.canDeliver(false))
        assertEquals(R.string.composer_restoring_attachments, state.unavailableReason(false, false, false))
    }

    @Test fun readyComposerHasNoWarningAndLocalCommandsNeedNoModel() {
        assertNull(ComposerAvailability().unavailableReason(false, false, false))
        val state = ComposerAvailability(modelSelected = false)
        assertEquals(R.string.chat_model_required_before_send, state.unavailableReason(false, false, false))
        assertTrue(state.canDeliver(true))
        assertNull(state.unavailableReason(true, false, false))
    }

    @Test fun modeAndPermissionProgressAreVisible() {
        val state = ComposerAvailability()
        assertEquals(R.string.composer_mode_switching, state.unavailableReason(false, true, false))
        assertEquals(R.string.composer_permission_saving, state.unavailableReason(false, false, true))
    }

    @Test fun missingReasonFallsBackToExplicitStatus() {
        assertEquals(
            R.string.composer_temporarily_unavailable,
            ComposerAvailability(delivery = false).unavailableReason(false, false, false),
        )
    }
}
