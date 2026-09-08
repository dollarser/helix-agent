package com.helix.app.goal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class GoalReminderPublicationTest {
    @Test
    fun failedWorkOperationCannotBeReportedAsSuccessfulCancellation() {
        val operation = CompletableFuture<Unit>()
        operation.completeExceptionally(IllegalStateException("storage failure"))
        assertThrows(IllegalStateException::class.java) { GoalReminderPublication.await(operation) }
    }

    @Test
    fun cancelledWorkOperationPropagatesCancellation() {
        val operation = CompletableFuture<Unit>()
        operation.cancel(false)
        assertThrows(CancellationException::class.java) { GoalReminderPublication.await(operation) }
    }

    @Test
    fun timedOutWorkOperationReportsFailureAndUsesBoundedWait() {
        val operation =
            object : CompletableFuture<Unit>() {
                override fun get(
                    timeout: Long,
                    unit: TimeUnit,
                ) {
                    assertEquals(10L, timeout)
                    assertEquals(TimeUnit.SECONDS, unit)
                    throw TimeoutException("fixture timeout")
                }
            }
        assertThrows(IllegalStateException::class.java) { GoalReminderPublication.await(operation) }
    }
}
