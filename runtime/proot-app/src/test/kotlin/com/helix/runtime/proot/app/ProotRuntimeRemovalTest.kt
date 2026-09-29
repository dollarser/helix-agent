package com.helix.runtime.proot.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotRuntimeRemovalTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun repeatedRemovalSucceedsWithoutTouchingAdjacentFiles() {
        val runtime = directory.newFolder("runtime")
        File(runtime, "partial").writeText("incomplete install")
        val workspace = File(directory.root, "workspace.txt").apply { writeText("keep") }
        assertTrue(ProotRuntimeRemoval.remove(runtime).removed)
        assertTrue(ProotRuntimeRemoval.remove(runtime).removed)
        assertFalse(runtime.exists())
        assertTrue(workspace.readText() == "keep")
    }

    @Test(expected = IllegalArgumentException::class)
    fun otherDirectoriesAreNeverRemoved() {
        ProotRuntimeRemoval.remove(directory.newFolder("workspace"))
    }
}
