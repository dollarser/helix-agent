package com.helix.extensions.mobileuse.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AccessibilityTargetObserverTest {
    @Test
    fun contentOnlyWindowReplacementInvalidatesButUnrelatedWindowContentDoesNot() {
        val tracker = AccessibilityGenerationTracker()
        var target: AccessibilityTarget? = AccessibilityTarget("allowed", 7)
        val observer =
            AccessibilityTargetObserver(
                executor = Executor { it.run() },
                query = { target },
                dispatch = { it() },
                observe = {},
                windowChanged = { tracker.contentChanged() },
            )
        observer.request(true)
        val verified = tracker.current()
        tracker.contentChangedInWindow(8, observer.activeWindow)
        observer.request(false)
        assertEquals(verified, tracker.current())
        tracker.contentChanged()
        observer.request(true)
        assertEquals(verified + 1, tracker.current())
        target = AccessibilityTarget("allowed", 8)
        observer.request(false)
        assertEquals(verified + 2, tracker.current())
        target = null
        observer.request(false)
        assertEquals(verified + 3, tracker.current())
    }

    @Test
    fun eventStormQueuesOneQueryAndPublishesLatestTarget() {
        val work = ArrayDeque<Runnable>()
        val delivery = ArrayDeque<() -> Unit>()
        val observed = mutableListOf<AccessibilityTarget>()
        var queries = 0
        val observer =
            AccessibilityTargetObserver(
                executor = Executor { work.add(it) },
                query = {
                    queries++
                    AccessibilityTarget("allowed", 7)
                },
                dispatch = { delivery.add(it) },
                observe = observed::add,
            )
        repeat(1000) { observer.request(true) }
        assertEquals(1, work.size)
        work.remove().run()
        assertEquals(1, queries)
        assertNull(observer.activeWindow)
        delivery.remove().invoke()
        assertEquals(7, observer.activeWindow)
        assertEquals(listOf(AccessibilityTarget("allowed", 7)), observed)
        observer.request(false)
        assertEquals(7, observer.activeWindow)
        observer.request(true)
        assertNull(observer.activeWindow)
    }

    @Test
    fun blockedQueryDoesNotBlockEventsAndCloseDoesNotReleasePhysicalCapacity() {
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val queries = AtomicInteger()
        val publications = AtomicInteger()
        val observer =
            AccessibilityTargetObserver(
                executor = executor,
                query = {
                    queries.incrementAndGet()
                    started.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    finished.countDown()
                    AccessibilityTarget("allowed", 7)
                },
                dispatch = { it() },
                observe = { publications.incrementAndGet() },
            )
        try {
            observer.request(true)
            assertTrue(started.await(2, TimeUnit.SECONDS))
            repeat(1000) { observer.request(true) }
            observer.close()
            observer.request(true)
            assertEquals(1, queries.get())
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            executor.shutdown()
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS))
            assertEquals(0, publications.get())
            assertNull(observer.activeWindow)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun eventDuringQueryDiscardsOldResultAndReadsLatestWindow() {
        val work = ArrayDeque<Runnable>()
        val delivery = ArrayDeque<() -> Unit>()
        val observed = mutableListOf<AccessibilityTarget>()
        var queries = 0
        lateinit var observer: AccessibilityTargetObserver
        observer =
            AccessibilityTargetObserver(
                executor = Executor { work.add(it) },
                query = {
                    queries++
                    if (queries == 1) observer.request(true)
                    AccessibilityTarget(if (queries == 1) "old" else "new", queries)
                },
                dispatch = { delivery.add(it) },
                observe = observed::add,
            )
        observer.request(true)
        work.remove().run()
        assertEquals(2, queries)
        assertEquals(1, delivery.size)
        delivery.remove().invoke()
        assertEquals(listOf(AccessibilityTarget("new", 2)), observed)
    }

    @Test
    fun queuedDeliveryCannotResumeAfterNewEventOrServiceClose() {
        val work = ArrayDeque<Runnable>()
        val delivery = ArrayDeque<() -> Unit>()
        val observed = mutableListOf<AccessibilityTarget>()
        val observer =
            AccessibilityTargetObserver(
                executor = Executor { work.add(it) },
                query = { AccessibilityTarget("allowed", 7) },
                dispatch = { delivery.add(it) },
                observe = observed::add,
            )
        observer.request(true)
        work.remove().run()
        observer.request(true)
        delivery.remove().invoke()
        assertTrue(observed.isEmpty())
        work.remove().run()
        observer.close()
        delivery.remove().invoke()
        assertTrue(observed.isEmpty())
        assertNull(observer.activeWindow)
    }

    @Test
    fun disappearingWindowClearsCacheWithoutReportingTarget() {
        val observed = mutableListOf<AccessibilityTarget>()
        var target: AccessibilityTarget? = AccessibilityTarget("allowed", 7)
        val observer =
            AccessibilityTargetObserver(
                executor = Executor { it.run() },
                query = { target },
                dispatch = { it() },
                observe = observed::add,
            )
        observer.request(true)
        target = null
        observer.request(false)
        assertNull(observer.activeWindow)
        assertEquals(1, observed.size)
    }

    @Test
    fun failedQueryDoesNotWedgeFutureObservationOrAuthorizeTarget() {
        val observed = mutableListOf<AccessibilityTarget>()
        var failed = true
        val observer =
            AccessibilityTargetObserver(
                executor = Executor { it.run() },
                query = {
                    check(!failed) { "window disappeared" }
                    AccessibilityTarget("allowed", 7)
                },
                dispatch = { it() },
                observe = observed::add,
            )
        observer.request(true)
        assertNull(observer.activeWindow)
        assertTrue(observed.isEmpty())
        failed = false
        observer.request(true)
        assertEquals(listOf(AccessibilityTarget("allowed", 7)), observed)
    }

    @Test
    fun busySharedWorkerCanRetryWithoutRunningQueryOnCaller() {
        var reject = true
        var queries = 0
        val observer =
            AccessibilityTargetObserver(
                executor = Executor { if (reject) throw RejectedExecutionException() else it.run() },
                query = {
                    queries++
                    AccessibilityTarget("allowed", 7)
                },
                dispatch = { it() },
                observe = {},
            )
        observer.request(true)
        assertEquals(0, queries)
        reject = false
        observer.request(true)
        assertEquals(1, queries)
    }
}
