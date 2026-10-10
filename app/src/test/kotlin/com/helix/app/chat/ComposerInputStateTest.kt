package com.helix.app.chat

import com.helix.feature.files.AttachmentClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ComposerInputStateTest {
    private fun entry(
        id: String,
        session: String = "session",
    ) = StagedAttachmentEntry(session, id, "$id.txt", 1, "digest", "$id.txt", Paths.get("$id.txt"))

    @Test fun concurrentImportsRespectTheSameAuthoritativeCap() {
        val state = ComposerInputState()
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results =
                (0 until 32).map { index ->
                    pool.submit<ComposerInputState.Admission> {
                        assertTrue(start.await(5, TimeUnit.SECONDS))
                        state.admit(entry("$index")) { "session" }
                    }
                }
            start.countDown()
            val admissions = results.map { it.get(5, TimeUnit.SECONDS) }
            val cap = AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE
            assertEquals(cap, admissions.count { it == ComposerInputState.Admission.ADDED })
            assertEquals(32 - cap, admissions.count { it == ComposerInputState.Admission.FULL })
            assertEquals(cap, state.stagedIds("session").distinct().size)
        } finally {
            start.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun lateImportAndRestoreCannotReplaceAnotherSessionsInput() {
        val state = ComposerInputState()
        assertEquals(ComposerInputState.Admission.ADDED, state.admit(entry("current", "other")) { "other" })
        assertEquals(ComposerInputState.Admission.STALE_SESSION, state.admit(entry("late")) { "other" })
        assertFalse(state.replaceStaged(listOf(entry("restored")), "session") { "other" })
        assertEquals(listOf("current"), state.stagedIds("other"))
        assertTrue(state.stagedIds("session").isEmpty())
    }

    @Test fun restoreOwnsSnapshotAndOldReceiptKeepsNewAttachments() {
        val state = ComposerInputState()
        val restored = mutableListOf(entry("accepted"))
        assertTrue(state.replaceStaged(restored, "session") { "session" })
        restored.clear()
        assertEquals(listOf("accepted"), state.stagedIds("session"))
        val oldSnapshot = state.staged
        assertEquals(ComposerInputState.Admission.ADDED, state.admit(entry("new")) { "session" })
        state.removeStaged(listOf("accepted"))
        assertEquals(listOf("new"), state.stagedIds("session"))
        assertEquals(listOf("accepted"), oldSnapshot.map { it.artifactId })
        state.clearStaged()
        assertTrue(state.staged.isEmpty())
    }
}
