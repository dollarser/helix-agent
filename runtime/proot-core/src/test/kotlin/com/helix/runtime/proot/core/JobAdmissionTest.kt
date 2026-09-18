package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class JobAdmissionTest {
    @Test fun cancellationBeforeStartNeverInvokesExecution() {
        val admission = JobAdmission()
        admission.cancel { Unit }
        assertNull(admission.start { error("must not execute") })
        admission.cancel { Unit }
        assertNull(admission.start { error("cancel cannot be reset") })
    }

    @Test fun cancellationDuringSubmissionRunsAfterTheExecutionIdentityExists() {
        val admission = JobAdmission()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val requested = CountDownLatch(1)
        val published = AtomicBoolean()
        val cancelled = AtomicBoolean()
        val submit =
            Thread {
                admission.start {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    published.set(true)
                }
            }
        val cancel =
            Thread {
                requested.countDown()
                admission.cancel { cancelled.set(published.get()) }
            }
        submit.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        cancel.start()
        assertTrue(requested.await(5, TimeUnit.SECONDS))
        assertFalse(cancelled.get())
        release.countDown()
        submit.join(5_000)
        cancel.join(5_000)
        assertEquals(true, cancelled.get())
    }
}
