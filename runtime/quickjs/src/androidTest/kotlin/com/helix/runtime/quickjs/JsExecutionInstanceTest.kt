package com.helix.runtime.quickjs

import android.os.IBinder
import android.os.Process
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CountDownLatch

/**
 * HXA-051 unique-instance lifecycle (roadmap M5 / doc 03 §2.2/§4.5):
 *
 * - every execution binds a unique `bindIsolatedService` instance (fresh PID);
 * - re-binding the same instance name while the instance is alive yields the SAME
 *   instance (the instance name is the dedupe key);
 * - one execution per instance lifetime (the slot is one-shot);
 * - `unbindService` lets the system reclaim the instance — observed through the Binder
 *   death signal (HXA-050 recommended replacing the deprecated process scan with this;
 *   the scan remains only as a secondary bounded observation).
 *
 * Isolation is asserted via PID/UID only; the process-name string is never an assertion
 * target or a protocol ID.
 */
class JsExecutionInstanceTest {
    @get:Rule
    val globalTimeout = Timeout.seconds(300)

    private val support = JsExecutionTestSupport

    @Test
    fun eachExecutionUsesFreshIsolatedInstance() {
        val first =
            support.client.execute(
                support.params(support.newExecutionId("fresh-1"), "1"),
            )
        val second =
            support.client.execute(
                support.params(support.newExecutionId("fresh-2"), "2"),
            )
        assertEquals(JsExecutionStatus.SUCCESS, first.status)
        assertEquals(JsExecutionStatus.SUCCESS, second.status)
        assertNotEquals("two executions must use different isolated instances", first.servicePid, second.servicePid)
        assertNotEquals("service must not share the caller UID", Process.myUid(), first.serviceUid)
        assertNotEquals("service must not share the caller PID", Process.myPid(), first.servicePid)
    }

    @Test
    fun rebindSameNameWhileAliveIsSameInstance() {
        val name = support.newInstanceName("rebind")
        val a = support.bindDirect(name)
        try {
            val b = support.bindDirect(name)
            try {
                assertEquals(
                    "re-binding the same instance name must reach the SAME instance",
                    a.pid,
                    b.pid,
                )
                assertEquals(a.uid, b.uid)
                // The same instance is fully functional through either connection.
                assertEquals(a.pid, support.info(b.binder).first)
            } finally {
                b.release()
            }
        } finally {
            a.release()
        }
    }

    @Test
    fun oneExecutionPerInstanceLifetime() {
        val name = support.newInstanceName("oneshot")
        val a = support.bindDirect(name)
        try {
            val first = support.executeDirect(a.binder, support.newExecutionId("oneshot-exec"), "1 + 1")
            assertEquals(JsExecutionStatus.SUCCESS, first.status)
            // The instance slot is one-shot: a second execution on the same instance is
            // rejected (doc 03 §4.1: a fresh instance per task; no shared state).
            val second = support.executeDirect(a.binder, support.newExecutionId("oneshot-exec-2"), "2 + 2")
            assertEquals(JsExecutionStatus.REQUEST_REJECTED, second.status)
            assertTrue(
                "rejection must state the instance is used, got: ${second.detail}",
                second.detail.contains("already used"),
            )
            // INFO still works on a used instance (identity observation is not gated).
            assertEquals(a.pid, support.info(a.binder).first)
        } finally {
            a.release()
        }
    }

    @Test
    fun unbindReclaimsInstanceViaBinderDeath() {
        val name = support.newInstanceName("reclaim")
        val bound = support.bindDirect(name)
        // Primary reclamation signal: the Binder death recipient (replaces the
        // deprecated process scan as the observation point per HXA-050).
        val died = CountDownLatch(1)
        val recipient =
            object : IBinder.DeathRecipient {
                override fun binderDied() {
                    died.countDown()
                }
            }
        bound.binder.linkToDeath(recipient, 0)
        bound.release()
        assertTrue(
            "binderDied must fire within 30 s after unbind (system reclamation)",
            died.await(30, java.util.concurrent.TimeUnit.SECONDS),
        )
        // Secondary bounded observation: the PID is gone from the app's processes.
        assertTrue(
            "reclaimed PID ${bound.pid} must disappear from running processes",
            support.awaitProcessGone(bound.pid, 10_000L),
        )
        runCatching { bound.binder.unlinkToDeath(recipient, 0) } // best effort after death
    }

    @Test
    fun sameNameAfterReclamationYieldsFreshInstance() {
        val executionId = support.newExecutionId("resame")
        val first = support.client.execute(support.params(executionId, "1"))
        assertEquals(JsExecutionStatus.SUCCESS, first.status)
        val firstPid = first.servicePid
        assertTrue(
            "first instance PID $firstPid must be reclaimed within 30 s",
            support.awaitProcessGone(firstPid, 30_000L),
        )
        // Same executionId → same derived instance name, but the previous instance is
        // gone, so the bind creates a FRESH instance (new PID, fresh slot).
        val second = support.client.execute(support.params(executionId, "2"))
        assertEquals(JsExecutionStatus.SUCCESS, second.status)
        assertNotEquals("re-bind after reclamation must be a fresh instance", firstPid, second.servicePid)
        assertEquals("2", second.outputUtf8.toString(Charsets.UTF_8))
    }
}
