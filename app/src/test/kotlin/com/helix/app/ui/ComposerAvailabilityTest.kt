package com.helix.app.ui

import com.helix.app.chat.hasSelectedConversationModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerAvailabilityTest {
    @Test fun missingModelBlocksDeliveryNotTypingAttachmentsOrLocalControls() {
        val availability = ComposerAvailability(modelSelected = false)
        assertTrue(availability.input)
        assertTrue(availability.canAttach())
        assertTrue(availability.canDeliver(localOnly = true))
        assertFalse(availability.canDeliver(localOnly = false))
    }

    @Test fun selectionDoesNotOverrideAnotherDeliveryConstraint() {
        assertTrue(ComposerAvailability(modelSelected = true).canDeliver(false))
        assertFalse(ComposerAvailability(delivery = false).canDeliver(false))
        assertFalse(ComposerAvailability(localCommands = false).canDeliver(true))
    }

    @Test fun providerAloneOrEmptyModelDoesNotCountAsSelection() {
        assertFalse(hasSelectedConversationModel(null, null))
        assertFalse(hasSelectedConversationModel("provider", null))
        assertFalse(hasSelectedConversationModel("provider", " "))
        assertFalse(hasSelectedConversationModel(null, "model"))
        assertFalse(hasSelectedConversationModel("", "model"))
        assertTrue(hasSelectedConversationModel("provider", "precise-model"))
    }
}
