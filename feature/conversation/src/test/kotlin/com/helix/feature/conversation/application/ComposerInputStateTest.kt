package com.helix.feature.conversation.application

import com.helix.core.model.input.InputAttachmentLimits
import com.helix.feature.conversation.application.attachment.StagedAttachmentCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ComposerInputStateTest {
    private fun entry(
        id: String,
        session: String = "session",
    ) = StagedAttachmentCandidate(session, id, "$id.txt", 1, "digest", "$id.txt")

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
            val cap = InputAttachmentLimits.MAX_ATTACHMENTS_PER_MESSAGE
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
        assertFalse(state.replaceStaged(listOf(entry("restored")), "session", state.stagedRevision) { "other" })
        assertEquals(listOf("current"), state.stagedIds("other"))
        assertTrue(state.stagedIds("session").isEmpty())
    }

    @Test fun restoreOwnsSnapshotAndOldReceiptKeepsNewAttachments() {
        val state = ComposerInputState()
        val restored = mutableListOf(entry("accepted"))
        assertTrue(state.replaceStaged(restored, "session", state.stagedRevision) { "session" })
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

    @Test fun restoreRejectsForeignDuplicateAndOverLimitSnapshotsWithoutReplacingCurrentInput() {
        val state = ComposerInputState()
        val original = entry("original")
        state.admit(original) { "session" }
        assertFalse(
            state.replaceStaged(listOf(entry("foreign", "other")), "session", state.stagedRevision) { "session" },
        )
        assertFalse(
            state.replaceStaged(listOf(entry("same"), entry("same")), "session", state.stagedRevision) { "session" },
        )
        val oversized = (0..InputAttachmentLimits.MAX_ATTACHMENTS_PER_MESSAGE).map { entry("$it") }
        assertFalse(state.replaceStaged(oversized, "session", state.stagedRevision) { "session" })
        assertEquals(listOf(original), state.staged)
    }

    @Test fun duplicateRetainsOriginalIdentityEvenAtCapacity() {
        val state = ComposerInputState()
        val full = (0 until InputAttachmentLimits.MAX_ATTACHMENTS_PER_MESSAGE).map { entry("$it") }
        assertTrue(state.replaceStaged(full, "session", state.stagedRevision) { "session" })
        val changed = full.first().copy(boundSha256 = "different-digest")
        assertEquals(ComposerInputState.Admission.DUPLICATE, state.admit(changed) { "session" })
        assertEquals(full, state.staged)
    }

    @Test fun explicitSendRequiresCurrentSessionAndExactOrderedEnumeration() {
        val state = ComposerInputState()
        state.admit(entry("a")) { "session" }
        state.admit(entry("b")) { "session" }
        assertEquals(state.staged, state.snapshotForSend("session", listOf("a", "b")))
        assertNull(state.snapshotForSend("other", listOf("a", "b")))
        assertNull(state.snapshotForSend("session", listOf("b", "a")))
        assertNull(state.snapshotForSend("session", listOf("a")))
        state.removeStaged(listOf("b"))
        assertNull(state.snapshotForSend("session", listOf("a", "b")))
    }

    @Test fun receiptOnlyConsumesTheExactAcceptedRawAndNormalizedIdentity() {
        val state = ComposerInputState()
        val accepted = entry("image").copy(normalizedArtifactId = "normalized", normalizedSha256 = "old")
        state.admit(accepted) { "session" }
        val snapshot = state.snapshotForSend("session", listOf("image"))!!
        val restored = accepted.copy(normalizedSha256 = "new")
        assertTrue(state.replaceStaged(listOf(restored), "session", state.stagedRevision) { "session" })
        state.consumeStaged(snapshot)
        assertEquals(listOf(restored), state.staged)
        state.admit(entry("later")) { "session" }
        state.consumeStaged(listOf(restored))
        assertEquals(listOf("later"), state.stagedIds("session"))
    }

    @Test fun inFlightRestoreCannotDiscardLaterSameSessionRemovalsOrImports() {
        val state = ComposerInputState()
        state.admit(entry("removed")) { "session" }
        val captured = CountDownLatch(1)
        val verified = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val restore =
                pool.submit<Boolean> {
                    val revision = state.stagedRevision
                    captured.countDown()
                    assertTrue(verified.await(5, TimeUnit.SECONDS))
                    state.replaceStaged(listOf(entry("removed")), "session", revision) { "session" }
                }
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            state.removeStaged(listOf("removed"))
            state.admit(entry("new")) { "session" }
            verified.countDown()
            assertFalse(restore.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("new"), state.stagedIds("session"))
        } finally {
            verified.countDown()
            pool.shutdownNow()
        }
    }
}
