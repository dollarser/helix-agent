package com.helix.runtime.quickjs

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * HXA-050 spike capability 6 (roadmap M5 + doc 03 §2.2/§4.5): `bindIsolatedService`
 * unique-instance creation and reclamation.
 *
 * Binds the minimal non-exported `isolatedProcess` service [SpikeIsolatedService] with
 * a unique instance name and verifies, from OUTSIDE the isolated process:
 * 1. the service runs in a different PID and a different (isolated) UID;
 * 2. the process name carries the instance name (the unique-instance key);
 * 3. after `unbindService` the system RECLAIMS the instance (PID gone);
 * 4. a re-bind with a NEW instance name yields a NEW PID (unique instance, not a
 *    shared/cached one).
 *
 * Probe observation (API 36 arm64-v8a): svcUid landed in the isolated-UID range
 * (99000) while the test process ran at a normal app UID; process name had the form
 * `<package>:<serviceClass>:<instanceName>`. The same framework call is what
 * `ContextCompat.bindIsolatedService` wraps (androidx.core is not on this module's
 * test classpath; minSdk 29 makes the framework overload directly available).
 *
 * Instance names here are deliberately TEMPORARY spike values — the production
 * `js_` + 32-hex naming, character/length/collision rules belong to HXA-051.
 */
class IsolatedServiceSpikeTest {
    @get:Rule
    val globalTimeout = Timeout.seconds(300)

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun isolatedInstanceHasOwnPidUidAndIsReclaimedOnUnbind() {
        val firstInstance = randomInstanceName()
        val first = bind(firstInstance)
        var firstStillBound = true
        try {
            // 1. PID + UID isolation.
            assertNotEquals("service must run in a different process", Process.myPid(), first.servicePid)
            assertNotEquals("service must run in a different (isolated) UID", Process.myUid(), first.serviceUid)
            // 2. Process name carries the instance name.
            assertTrue(
                "process name ${first.processName} must end with :$firstInstance",
                first.processName.endsWith(":$firstInstance"),
            )

            // 3. Unbind → the instance process is reclaimed by the system.
            context.unbindService(first.connection)
            firstStillBound = false
            assertProcessGone(first.servicePid)

            // 4. Re-bind with a NEW instance name → a NEW instance (different PID).
            val secondInstance = randomInstanceName()
            val second = bind(secondInstance)
            try {
                assertNotEquals("re-bind must yield a fresh instance", first.servicePid, second.servicePid)
                assertTrue(
                    "process name ${second.processName} must end with :$secondInstance",
                    second.processName.endsWith(":$secondInstance"),
                )
                // The fresh instance is fully functional.
                assertInfoRoundTrip(second.binder)
            } finally {
                context.unbindService(second.connection)
            }
            assertProcessGone(second.servicePid)
        } finally {
            // Safety net: never leave a spike instance bound on assertion failure.
            if (firstStillBound) {
                context.unbindService(first.connection)
            }
        }
    }

    private data class BoundInstance(
        val binder: IBinder,
        val servicePid: Int,
        val serviceUid: Int,
        val processName: String,
        val connection: ServiceConnection,
    )

    private val instanceCounter = AtomicInteger(0)

    private fun randomInstanceName(): String =
        "js_spike_" + System.nanoTime().toString(16) + instanceCounter.incrementAndGet().toString(16)

    private fun bind(instanceName: String): BoundInstance {
        val connected = CountDownLatch(1)
        var binder: IBinder? = null
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    binder = service
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // The test consumes only onServiceConnected; an early disconnect
                    // would simply time out the bind latch below.
                }
            }
        val ok =
            context.bindIsolatedService(
                Intent(context, SpikeIsolatedService::class.java),
                Context.BIND_AUTO_CREATE,
                instanceName,
                context.mainExecutor,
                connection,
            )
        assertTrue("bindIsolatedService must succeed for $instanceName", ok)
        assertTrue(
            "onServiceConnected did not fire within 15 s for $instanceName",
            connected.await(15, TimeUnit.SECONDS),
        )
        val info = transactForInfo(binder!!)
        return BoundInstance(binder!!, info.pid, info.uid, info.process, connection)
    }

    private data class ServiceInfo(
        val pid: Int,
        val uid: Int,
        val process: String,
    )

    private fun transactForInfo(binder: IBinder): ServiceInfo {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            binder.transact(SpikeIsolatedService.InfoBinder.CODE_INFO, data, reply, 0)
            val bundle = reply.readBundle(null)
            assertNotNull("service must answer the info transaction", bundle)
            return ServiceInfo(
                bundle!!.getInt(SpikeIsolatedService.InfoBinder.KEY_PID),
                bundle.getInt(SpikeIsolatedService.InfoBinder.KEY_UID),
                bundle.getString(SpikeIsolatedService.InfoBinder.KEY_PROCESS) ?: "",
            )
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun assertInfoRoundTrip(binder: IBinder) {
        val info = transactForInfo(binder)
        assertTrue("service process name must be non-empty", info.process.isNotEmpty())
    }

    // Deprecated API, retained intentionally: on API 29+ it still reports the
    // calling app's own processes (including isolated ones) with valid PIDs, which is
    // exactly the reclamation signal this spike needs. HXA-051 will replace the
    // observation point with Binder death signals.
    @Suppress("DEPRECATION")
    private fun assertProcessGone(pid: Int) {
        val deadline = System.nanoTime() + 20L * 1_000_000_000
        while (System.nanoTime() < deadline) {
            val pids =
                (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
                    .runningAppProcesses
                    ?.mapNotNull { it.pid }
                    ?: emptyList()
            if (pid !in pids) {
                return
            }
            Thread.sleep(100)
        }
        assertTrue(
            "isolated service PID $pid must be reclaimed within 20 s after unbind",
            false,
        )
    }
}
