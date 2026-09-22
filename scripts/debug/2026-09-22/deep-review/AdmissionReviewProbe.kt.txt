package com.helix.app.chat

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AdmissionReviewProbe {
    @Test fun cancellingWorkerRetainsAdmissionUntilItsFinallyFinishes() = runBlocking {
        val admission = SessionTurnAdmission()
        val entered = CompletableDeferred<Unit>()
        val cleaning = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()
        val old = launch {
            try { entered.complete(Unit); awaitCancellation() }
            finally { withContext(NonCancellable) { cleaning.complete(Unit); allowCleanup.await() } }
        }
        admission.register("session", old, "old-turn")
        entered.await()
        old.cancel()
        cleaning.await()
        try {
            assertFalse("old worker has not completed", old.isCompleted)
            assertTrue("cancelling worker retains admission", admission.hasActive("session"))
            assertEquals("old-turn", admission.activeTurn("session")!!.turnId)
            assertEquals(listOf("old-turn"), admission.activeTurns().map { it.turnId })
            println("FIXED admission: oldCompleted=${old.isCompleted}, owner=${admission.activeTurn("session")!!.turnId}")
        } finally { allowCleanup.complete(Unit); old.join() }
        assertFalse(admission.hasActive("session"))
        assertNull(admission.activeTurn("session"))
    }
}
