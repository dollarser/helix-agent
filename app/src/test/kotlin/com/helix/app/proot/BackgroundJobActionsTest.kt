package com.helix.app.proot

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundJobActionsTest {
    private val original = BackgroundJobUi("call", "turn", "session", "Job", CommandDetailState.SUBMITTED, true)

    @Test
    fun duplicateClicksRemainExcludedUntilRefreshFinishes() =
        runBlocking {
            val refreshed = CompletableDeferred<Unit>()
            var executions = 0
            val actions =
                BackgroundJobActions(this, { job, _, _ ->
                    assertEquals(original, job)
                    executions++
                    BackgroundJobActionOutcome.ACTIVE
                }) { refreshed.await() }
            actions.submit(original, BackgroundJobAction.QUERY)
            actions.submit(original, BackgroundJobAction.CANCEL)
            yield()
            actions.submit(original, BackgroundJobAction.COLLECT)
            assertEquals(1, executions)
            assertTrue(requireNotNull(actions.state.value).busy)
            refreshed.complete(Unit)
            yield()
            actions.submit(original, BackgroundJobAction.CANCEL)
            yield()
            assertEquals(2, executions)
            assertFalse(requireNotNull(actions.state.value).busy)
        }

    @Test
    fun executorFailureDoesNotStrandTheActionGate() =
        runBlocking {
            var executions = 0
            val actions =
                BackgroundJobActions(this, { _, _, _ ->
                    executions++
                    error("fixture storage failure")
                }) { error("must not refresh failed execution") }
            actions.submit(original, BackgroundJobAction.QUERY)
            yield()
            assertEquals(BackgroundJobActionOutcome.FAILED, actions.state.value?.outcome)
            actions.submit(original, BackgroundJobAction.QUERY)
            yield()
            assertEquals(2, executions)
        }

    @Test
    fun cancelledScopeDoesNotLeavePermanentBusyState() =
        runBlocking {
            val scope = CoroutineScope(coroutineContext + Job())
            scope.cancel()
            var executions = 0
            val actions =
                BackgroundJobActions(scope, { _, _, _ ->
                    executions++
                    BackgroundJobActionOutcome.ACTIVE
                }) {}
            actions.submit(original, BackgroundJobAction.QUERY)
            yield()
            assertFalse(requireNotNull(actions.state.value).busy)
            actions.submit(original, BackgroundJobAction.QUERY)
            yield()
            assertFalse(requireNotNull(actions.state.value).busy)
            assertEquals(0, executions)
        }
}
