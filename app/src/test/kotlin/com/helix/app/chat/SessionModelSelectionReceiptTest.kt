package com.helix.app.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SessionModelSelectionReceiptTest {
    @Test fun rejectionReportsTheActualBoundary() {
        assertEquals(SessionModelSelectionResult.SESSION_CHANGED, selectionRejection(null, "s", false, true, true))
        assertEquals(SessionModelSelectionResult.SESSION_CHANGED, selectionRejection("s", "other", false, true, true))
        assertEquals(SessionModelSelectionResult.BUSY, selectionRejection("s", "s", true, true, true))
        assertEquals(SessionModelSelectionResult.PROVIDER_UNAVAILABLE, selectionRejection("s", "s", false, false, true))
        assertEquals(SessionModelSelectionResult.MODEL_UNAVAILABLE, selectionRejection("s", "s", false, true, false))
        assertNull(selectionRejection("s", "s", false, true, true))
    }

    @Test fun receiptCompletesAfterCommitAndFollowingSendUsesCommittedTarget() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val queue = SessionActionQueue(scope)
                val commit = CompletableDeferred<Unit>()
                var model = "a"
                val selection =
                    queue.submit {
                        commit.await()
                        model = "b"
                        SessionModelSelectionResult.APPLIED
                    }
                val send = queue.submit { model }
                assertFalse(selection.isCompleted)
                assertFalse(send.isCompleted)
                commit.complete(Unit)
                assertEquals(SessionModelSelectionResult.APPLIED, selection.await())
                assertEquals("b", send.await())
            } finally {
                scope.cancel()
            }
        }

    @Test fun delayedDialogTargetsItsOriginalSessionAndLeavesNewSessionUnchanged() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val queue = SessionActionQueue(scope)
                var session = "original"
                var model = "a"
                val release = CompletableDeferred<Unit>()
                queue.submit {
                    release.await()
                    session = "replacement"
                }
                val receipt =
                    queue.submit {
                        selectionRejection("original", session, false, true, true)
                            ?: run {
                                model = "b"
                                SessionModelSelectionResult.APPLIED
                            }
                    }
                release.complete(Unit)
                assertEquals(SessionModelSelectionResult.SESSION_CHANGED, receipt.await())
                assertEquals("a", model)
            } finally {
                scope.cancel()
            }
        }
}
