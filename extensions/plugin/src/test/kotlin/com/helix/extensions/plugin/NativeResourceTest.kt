package com.helix.extensions.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NativeResourceTest {
    @Test fun retiringOneConsumerPreservesOtherConsumerAndOriginalCallbackReference() {
        var cleaned = 0
        val resource = NativeResource(Any()) { cleaned++ }
        val first = requireNotNull(resource.borrow())
        val second = requireNotNull(resource.borrow())
        val callback = first.retain()
        resource.close()
        assertNull(resource.borrow())
        assertFalse(first.accepting)
        first.close()
        second.close()
        assertEquals(NativeResource.State.DRAINING, resource.state)
        assertEquals(0, cleaned)
        callback.close()
        callback.close()
        resource.close()
        assertEquals(NativeResource.State.CLOSED, resource.state)
        assertEquals(1, cleaned)
    }

    @Test fun cleanupFailureRemainsFailedAndCannotBeReportedAsClosedOrRetriedImplicitly() {
        val failure = IllegalStateException("synthetic cleanup failure")
        var attempts = 0
        val resource =
            NativeResource(Any()) {
                attempts++
                throw failure
            }
        val use = requireNotNull(resource.borrow())
        resource.close()
        assertSame(failure, assertThrows(IllegalStateException::class.java) { use.close() })
        assertSame(failure, resource.failure)
        assertEquals(NativeResource.State.CLEANUP_FAILED, resource.state)
        assertNull(resource.borrow())
        use.close()
        assertSame(failure, assertThrows(IllegalStateException::class.java) { resource.close() })
        assertEquals(1, attempts)
    }

    @Test fun concurrentLastReleasesCleanOnlyOnceAndCleanupRunsOutsideResourceLock() {
        val entered = CountDownLatch(1)
        val exit = CountDownLatch(1)
        val resource =
            NativeResource(Any()) {
                entered.countDown()
                check(exit.await(5, TimeUnit.SECONDS))
            }
        val first = requireNotNull(resource.borrow())
        val second = requireNotNull(resource.borrow())
        resource.close()
        first.close()
        val thread = Thread { second.close() }.apply { start() }
        try {
            check(entered.await(5, TimeUnit.SECONDS))
            assertEquals(NativeResource.State.CLEANING, resource.state)
            assertNull(resource.borrow())
            assertThrows(IllegalStateException::class.java) { first.retain() }
        } finally {
            exit.countDown()
            thread.join(5000)
        }
        assertEquals(false, thread.isAlive)
        assertEquals(NativeResource.State.CLOSED, resource.state)
    }
}
