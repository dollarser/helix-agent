package com.helix.extensions.mobileuse.tools

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTargetHintTest {
    @Test fun emptyTaskScreenReportsAMissingObjectWithAnActionableNextStep() {
        val hint = requireNotNull(missingTargetHint("TARGET_UNAVAILABLE"))
        assertTrue(hint.contains("No actionable app or window"))
        assertTrue(hint.contains("ui.launch"))
        assertNull(missingTargetHint("UNSUPPORTED_UI"))
        assertNull(missingTargetHint("ROOT_UNAVAILABLE"))
        assertNull(missingTargetHint("TARGET_NOT_ALLOWLISTED"))
    }
}
