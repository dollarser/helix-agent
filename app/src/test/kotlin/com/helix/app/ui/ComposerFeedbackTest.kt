package com.helix.app.ui

import com.helix.app.R
import com.helix.feature.conversation.presentation.ComposerAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.helix.feature.conversation.R as ConversationR

class ComposerFeedbackTest {
    @Test fun blockedDeliveryExplainsTheActualReason() {
        val state = ComposerAvailability(delivery = false, deliveryReason = R.string.composer_restoring_attachments)
        assertFalse(state.canDeliver(false))
        assertEquals(R.string.composer_restoring_attachments, state.unavailableReason(false, false, false))
    }

    @Test fun readyComposerHasNoWarningAndLocalCommandsNeedNoModel() {
        assertNull(ComposerAvailability().unavailableReason(false, false, false))
        val state = ComposerAvailability(modelSelected = false)
        assertNull(state.unavailableReason(false, false, false))
        assertTrue(state.canDeliver(true))
        assertNull(state.unavailableReason(true, false, false))
    }

    @Test fun modeAndPermissionProgressAreVisible() {
        val state = ComposerAvailability()
        assertEquals(ConversationR.string.composer_mode_switching, state.unavailableReason(false, true, false))
        assertEquals(ConversationR.string.composer_permission_saving, state.unavailableReason(false, false, true))
    }

    @Test fun missingReasonFallsBackToExplicitStatus() {
        assertEquals(
            ConversationR.string.composer_temporarily_unavailable,
            ComposerAvailability(delivery = false).unavailableReason(false, false, false),
        )
    }
}
