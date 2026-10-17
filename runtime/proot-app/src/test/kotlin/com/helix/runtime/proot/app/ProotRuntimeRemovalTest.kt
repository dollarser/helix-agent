package com.helix.runtime.proot.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

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

    @Test fun environmentLinksNeverDeleteWorkspaceContents() {
        val runtime = directory.newFolder("runtime")
        val workspace = directory.newFolder("workspace")
        val saved = File(workspace, "saved.txt").apply { writeText("keep") }
        Files.createSymbolicLink(File(runtime, "workspace").toPath(), workspace.toPath())
        assertTrue(ProotRuntimeRemoval.remove(runtime).removed)
        assertTrue(saved.readText() == "keep")
    }

    @Test fun aLinkedRootRemovesOnlyTheLink() {
        val workspace = directory.newFolder("workspace")
        val saved = File(workspace, "saved.txt").apply { writeText("keep") }
        val runtime = File(directory.root, "runtime")
        Files.createSymbolicLink(runtime.toPath(), workspace.toPath())
        assertTrue(ProotRuntimeRemoval.remove(runtime).removed)
        assertFalse(Files.exists(runtime.toPath(), NOFOLLOW_LINKS))
        assertTrue(saved.readText() == "keep")
    }

    @Test fun aDanglingRootLinkIsActuallyRemoved() {
        val runtime = File(directory.root, "runtime")
        Files.createSymbolicLink(runtime.toPath(), File(directory.root, "missing").toPath())
        assertTrue(ProotRuntimeRemoval.remove(runtime).removed)
        assertFalse(Files.exists(runtime.toPath(), NOFOLLOW_LINKS))
    }

    @Test fun discardedJobPayloadPreservesLinkedWorkspaceFiles() {
        val store = ProotJobStore(directory.newFolder("runtime"))
        val jobId = "job_abcdef123456"
        val payload = File(store.jobDir(jobId), "payload").apply { mkdirs() }
        val workspace = directory.newFolder("workspace")
        val saved = File(workspace, "saved.txt").apply { writeText("keep") }
        val record = store.recordFile(jobId).apply { writeText("record retained") }
        Files.createSymbolicLink(File(payload, "link").toPath(), workspace.toPath())
        store.deletePayload(jobId)
        assertFalse(payload.exists())
        assertTrue(saved.readText() == "keep")
        assertTrue(record.readText() == "record retained")
    }
}
