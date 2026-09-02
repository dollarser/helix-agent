package com.helix.app.chat

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the per-session turn admission extracted from [ChatService] (HXA-048). The
 * heavy [ChatService] cannot be constructed on the JVM (concrete Room-backed deps, no Robolectric),
 * so the admission logic lives in the pure [SessionTurnAdmission] and is tested here directly; the
 * exhaustive per-session concurrency/cancel end-to-end behavior remains in the instrumented column.
 */
class SessionTurnAdmissionTest {
    @Test
    fun admitsFirstTurnAndKeepsItActiveForThatSession() {
        val admission = SessionTurnAdmission()
        val job = Job()
        admission.register("s", job, "t1")

        // the admission gate re-reads hasActive for a second send into the same session
        assertTrue(admission.hasActive("s"))
        assertTrue(admission.hasActive("s"))
    }

    @Test
    fun aTurnInOneSessionDoesNotBlockAnotherSession() {
        val admission = SessionTurnAdmission()
        admission.register("a", Job(), "ta")

        assertFalse("session b slot must be free while a runs", admission.hasActive("b"))
        admission.register("b", Job(), "tb")

        assertTrue(admission.hasActive("a"))
        assertTrue(admission.hasActive("b"))
    }

    @Test
    fun aCompletedTurnFreesItsSessionSlot() {
        val admission = SessionTurnAdmission()
        val job = Job()
        admission.register("s", job, "t1")
        assertTrue(admission.hasActive("s"))

        job.cancel()

        assertFalse(admission.hasActive("s"))
        assertNull(admission.activeTurn("s"))
    }

    @Test
    fun activeTurnExposesTheJobAndTurnIdForTheStopPath() {
        val admission = SessionTurnAdmission()
        val job = Job()
        admission.register("s", job, "t42")

        val active = admission.activeTurn("s")
        assertNotNull(active)
        assertEquals(job, active?.job)
        assertEquals("t42", active?.turnId)
    }

    @Test
    fun aStaleCompletionNeverDropsASupersedingTurn() {
        val admission = SessionTurnAdmission()
        val first = Job()
        admission.register("s", first, "t1")

        // a new turn takes the slot (the first is no longer the tracked entry)
        val second = Job()
        admission.register("s", second, "t2")
        assertEquals("t2", admission.activeTurn("s")?.turnId)

        // the superseded turn completes: its self-removal must not drop the live turn
        first.cancel()
        assertTrue(admission.hasActive("s"))
        assertEquals("t2", admission.activeTurn("s")?.turnId)
    }
}
