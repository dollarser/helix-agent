package com.helix.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveHelixNavigationTest {
    @Test
    fun phonesAndShortLandscapeDoNotLoseConversationWidth() {
        assertFalse(permanentNavigation(390, 844, 1f))
        assertFalse(permanentNavigation(844, 390, 1f))
        assertFalse(permanentNavigation(1100, 420, 1f))
    }

    @Test
    fun expandedLayoutKeepsNavigationVisibleWithoutCrowdingLargeText() {
        assertTrue(permanentNavigation(1280, 900, 1f))
        assertFalse(permanentNavigation(1000, 800, 2f))
        assertTrue(permanentNavigation(1280, 900, 2f))
    }
}
