package com.helix.app.knowledge

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticModelDirectoryTest {
    @Test fun missingSavedModelNeverBecomesAnAvailableSelection() {
        val partial = SemanticModelDirectory(listOf("text-embedding-3-small"), partial = true)
        assertTrue(partial.missingSelection("local"))
        assertFalse(partial.canEnable("local", 1, false))
        assertFalse(partial.canEnable("", 1, false))
        assertTrue(partial.canEnable("text-embedding-3-small", 1, false))
        val restored = SemanticModelDirectory(listOf("local", "text-embedding-3-small"))
        assertFalse(restored.missingSelection("local"))
        assertTrue(restored.canEnable("local", 1, false))
    }

    @Test fun completeRevocationAndUnavailableOrEmptySelectionCannotEnable() {
        val complete = SemanticModelDirectory(listOf("cloud"))
        assertFalse(complete.canEnable("revoked", 1, false))
        assertFalse(complete.canEnable("cloud", 1, true))
        assertFalse(complete.canEnable("cloud", 0, false))
        assertFalse(complete.canEnable("cloud", 65, false))
    }
}
