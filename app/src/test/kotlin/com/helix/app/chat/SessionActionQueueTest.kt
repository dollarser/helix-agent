package com.helix.app.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionActionQueueTest {
    @Test fun sendWaitsForEarlierEditsEvenWhenTheDispatcherStartsItFirst() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val queue = SessionActionQueue(scope)
                val release = CompletableDeferred<Unit>()
                val started = CompletableDeferred<Unit>()
                var mode = "ACT"
                queue.submit {
                    started.complete(Unit)
                    release.await()
                    mode = "PLAN"
                }
                started.await()
                val send = queue.submit { mode }
                assertFalse(send.isCompleted)
                release.complete(Unit)
                assertEquals("PLAN", send.await())
            } finally {
                scope.cancel()
            }
        }

    @Test fun cancellingAReceiptCannotLetLaterSendsOvertakeAnAcceptedEdit() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val queue = SessionActionQueue(scope)
                val release = CompletableDeferred<Unit>()
                val order = mutableListOf<Int>()
                queue
                    .submit {
                        release.await()
                        order += 1
                    }.cancel()
                val send =
                    queue.submit {
                        order += 2
                        order.toList()
                    }
                assertFalse(send.isCompleted)
                release.complete(Unit)
                assertEquals(listOf(1, 2), send.await())
            } finally {
                scope.cancel()
            }
        }

    @Test fun failedActionAndCancelledOwnerNeverLeaveReceiptsWaitingForever() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val queue = SessionActionQueue(scope)
            try {
                val failed = queue.submit { error("fixture") }
                assertTrue(runCatching { failed.await() }.isFailure)
                assertEquals(42, queue.submit { 42 }.await())
            } finally {
                scope.cancel()
            }
            assertTrue(runCatching { queue.submit { 0 }.await() }.isFailure)
        }
}
