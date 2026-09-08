package com.helix.app.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportActionStateTest {
    @Test fun repeatedClickDoesNotStartASecondOperation() =
        runBlocking {
            val state = ImportActionState(this)
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            state.launch {
                calls++
                gate.await()
            }
            state.launch { calls++ }
            assertTrue(state.busy)
            assertEquals(1, calls)
            gate.complete(Unit)
            yield()
            assertFalse(state.busy)
            state.launch { calls++ }
            assertEquals(2, calls)
        }

    @Test fun failureIsClearedBySuccessfulRetry() =
        runBlocking {
            val state = ImportActionState(this)
            var diagnostic: String? = null
            state.launch(onFailure = { diagnostic = it.message }) { error("invalid manifest") }
            assertTrue(state.failed)
            assertFalse(state.busy)
            assertEquals("invalid manifest", diagnostic)
            state.launch { }
            assertFalse(state.failed)
        }

    @Test fun disposalCancelsWorkWithoutReportingFailure() =
        runBlocking {
            val owner = Job()
            val state = ImportActionState(CoroutineScope(coroutineContext + owner))
            var cleaned = false
            state.launch {
                try {
                    CompletableDeferred<Unit>().await()
                } finally {
                    cleaned = true
                }
            }
            owner.cancelAndJoin()
            assertTrue(cleaned)
            assertFalse(state.busy)
            assertFalse(state.failed)
        }
}
