package com.helix.app.engine

import com.helix.app.runcontrol.GoalBudgetDefaults
import com.helix.app.runcontrol.RunControlConfig
import com.helix.core.model.AgentMode
import com.helix.core.model.ReasoningEffort
import com.helix.core.model.TurnBudgets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnLiveRegistryTest {
    @Test
    fun claimOwnsSessionTurnControlAndCancelSignal() {
        val registry = TurnLiveRegistry()
        val job = Job()

        val handle = registry.claim("s", "t1", job, control)

        assertSame(handle, registry.active("s"))
        assertSame(handle, registry.byTurn("t1"))
        assertEquals(control, handle.control)
        assertFalse(handle.cancel.isCancelled())
        handle.signalCancel()
        assertTrue(handle.cancel.isCancelled())
    }

    @Test
    fun secondLiveTurnForSameSessionFailsClosed() {
        val registry = TurnLiveRegistry()
        registry.claim("s", "t1", Job(), control)

        assertThrows(IllegalArgumentException::class.java) {
            registry.claim("s", "t2", Job(), control)
        }
    }

    @Test
    fun durableReleaseFreesSessionBeforeJobCompletion() {
        val registry = TurnLiveRegistry()
        val firstJob = Job()
        registry.claim("s", "t1", firstJob, control)

        val released = registry.release("s", "t1")

        assertEquals("t1", released?.turnId)
        assertFalse(firstJob.isCompleted)
        assertNull(registry.active("s"))
        assertNull(registry.byTurn("t1"))
        registry.claim("s", "t2", Job(), control)
        assertEquals("t2", registry.active("s")?.turnId)
    }

    @Test
    fun staleJobCompletionCannotReleaseNewerSessionOwner() =
        runBlocking {
            val registry = TurnLiveRegistry()
            val settling = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        withContext(NonCancellable) {
                            settling.complete(Unit)
                            release.await()
                        }
                    }
                }
            registry.claim("s", "t1", first, control)
            first.cancel()
            settling.await()
            assertTrue(registry.hasActive("s"))

            registry.release("s", "t1")
            registry.claim("s", "t2", Job(), control)
            release.complete(Unit)
            first.join()

            assertEquals("t2", registry.active("s")?.turnId)
        }

    @Test
    fun naturalJobCompletionReleasesItsClaim() {
        val registry = TurnLiveRegistry()
        val job = Job()
        registry.claim("s", "t1", job, control)

        job.complete()

        assertNull(registry.active("s"))
        assertNull(registry.byTurn("t1"))
    }

    @Test
    fun turnInOneSessionDoesNotBlockAnotherSessionAndActiveTurnsListsAllLive() {
        val registry = TurnLiveRegistry()
        val jobA = Job()
        val jobB = Job()
        val handleA = registry.claim("a", "ta", jobA, control)
        val handleB = registry.claim("b", "tb", jobB, control)

        assertTrue(registry.hasActive("a"))
        assertTrue(registry.hasActive("b"))
        assertEquals(listOf(handleA, handleB), registry.activeTurns())

        jobA.complete()
        assertEquals(listOf(handleB), registry.activeTurns())
        assertFalse(registry.hasActive("a"))
        assertTrue(registry.hasActive("b"))
    }

    @Test
    fun staleReleaseDoesNotDropNewerTurn() {
        val registry = TurnLiveRegistry()
        val job2 = Job()
        registry.claim("s", "t2", job2, control)

        val released = registry.release("s", "t1")
        assertNull(released)
        assertTrue(registry.hasActive("s"))
        assertEquals("t2", registry.active("s")?.turnId)
    }

    private val control =
        RunControlConfig(
            mode = AgentMode.ACT,
            chatToolsEnabled = true,
            budgets = TurnBudgets(4, 4, 8_000, 2_000, 16_000),
            reasoning = ReasoningEffort.LOW,
            goalBudgets = GoalBudgetDefaults.VALUE,
        )
}
