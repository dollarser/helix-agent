package com.helix.tools.files

import com.helix.core.workspace.PreconditionHashMismatch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileVersionConflictTest {
    @Test fun missingAndUnconfirmedVersionsNeverPretendToBeAHash() {
        for ((actual, shown) in listOf("missing" to "missing", "source-changed" to "unknown (changed)")) {
            val text = fileVersionConflict("scope:ws:main.txt", PreconditionHashMismatch("0".repeat(64), actual))
            assertTrue(text.contains("observedSha256=$shown"))
            assertTrue(text.contains("Re-read the file"))
            assertFalse(text.contains("overwrite"))
        }
    }
}
