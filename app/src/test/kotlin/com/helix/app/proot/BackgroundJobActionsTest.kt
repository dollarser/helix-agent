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
    fun duplicateControlClicksRemainExcludedUntilRefreshFinishes() =
        runBlocking {
            val refreshed = CompletableDeferred<Unit>()
            var executions = 0
            val actions =
                BackgroundJobActions(this, { job, _, _ ->
                    assertEquals(original, job)
                    executions++
                    BackgroundJobActionOutcome.ACTIVE
                }) { refreshed.await() }
            actions.submit(original, BackgroundJobAction.CANCEL)
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
    fun pendingQueryDoesNotBlockControlAndCannotOverwriteItsLaterReceipt() =
        runBlocking {
            val firstRefresh = CompletableDeferred<Unit>()
            var executions = 0
            var refreshes = 0
            val actions =
                BackgroundJobActions(this, { _, action, _ ->
                    executions++
                    if (action == BackgroundJobAction.QUERY) {
                        BackgroundJobActionOutcome.ACTIVE
                    } else {
                        BackgroundJobActionOutcome.STOP_REQUESTED
                    }
                }) {
                    if (refreshes++ == 0) firstRefresh.await()
                }
            actions.submit(original, BackgroundJobAction.QUERY)
            yield()
            val querying = requireNotNull(actions.state.value)
            assertTrue(querying.allows(BackgroundJobAction.CANCEL))
            assertTrue(querying.allows(BackgroundJobAction.STOP_WAITING))
            assertFalse(querying.allows(BackgroundJobAction.QUERY))
            actions.submit(original, BackgroundJobAction.QUERY)
            actions.submit(original, BackgroundJobAction.CANCEL)
            yield()
            assertEquals(2, executions)
            assertEquals(BackgroundJobActionOutcome.STOP_REQUESTED, actions.state.value?.outcome)
            assertTrue(requireNotNull(actions.state.value).queryBusy)
            assertFalse(requireNotNull(actions.state.value).controlBusy)
            firstRefresh.complete(Unit)
            yield()
            assertFalse(requireNotNull(actions.state.value).queryBusy)
            assertEquals(BackgroundJobAction.CANCEL, actions.state.value?.action)
            assertEquals(BackgroundJobActionOutcome.STOP_REQUESTED, actions.state.value?.outcome)
        }

    @Test
    fun failedWaitControlDoesNotReleaseOrCancelAnIndependentQuery() =
        runBlocking {
            val firstRefresh = CompletableDeferred<Unit>()
            val actions =
                BackgroundJobActions(this, { _, action, _ ->
                    if (action == BackgroundJobAction.STOP_WAITING) error("fixture stop failure")
                    BackgroundJobActionOutcome.ACTIVE
                }) { firstRefresh.await() }
            actions.submit(original, BackgroundJobAction.QUERY)
            yield()
            actions.submit(original, BackgroundJobAction.STOP_WAITING)
            yield()
            assertEquals(BackgroundJobActionOutcome.FAILED, actions.state.value?.outcome)
            assertTrue(requireNotNull(actions.state.value).queryBusy)
            assertFalse(requireNotNull(actions.state.value).controlBusy)
            firstRefresh.complete(Unit)
            yield()
            assertFalse(requireNotNull(actions.state.value).queryBusy)
            assertEquals(BackgroundJobActionOutcome.FAILED, actions.state.value?.outcome)
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
