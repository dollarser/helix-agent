package com.helix.app.chat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.test.BlockedObservationService
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.JobObservation
import com.helix.tools.framework.JobObservationBinding
import com.helix.tools.framework.JobObservationLimits
import com.helix.tools.framework.JobObservationPort
import com.helix.tools.framework.JobObservationService
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Production observation lane with an actual remote Binder fixture, not a production Runtime query. */
class JobObservationBinderDeviceTest {
    @Test fun cancellationTimeoutAndRevocationKeepThePhysicalBinderSlotUntilReturn() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val component = ComponentName(instrumentation.context, BlockedObservationService::class.java)
        val intent = Intent().setComponent(component)
        val connected = CompletableFuture<IBinder>()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    connected.complete(service)
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE))
        try {
            val remote = connected.get(5, TimeUnit.SECONDS)
            assertNotEquals(Process.myPid(), status(remote)[0])
            verify(remote)
        } finally {
            connected.getNow(null)?.let { transaction(it, BlockedObservationService.RELEASE) }
            context.unbindService(connection)
            context.stopService(intent)
        }
    }

    private fun verify(remote: IBinder) {
        val current = AtomicBoolean(true)
        val port =
            object : JobObservationPort {
                override fun resolve(
                    sessionId: String,
                    handle: String,
                ) = binding(handle)

                override fun isCurrent(binding: JobObservationBinding) = current.get()

                override fun query(binding: JobObservationBinding): JobObservation {
                    transaction(remote, BlockedObservationService.QUERY)
                    return JobObservation(binding, "SUCCEEDED", true, false, true, "done", 1, 0)
                }
            }
        val clock =
            object : Clock {
                override fun now(): Instant = Instant.now()
            }
        val limits = JobObservationLimits(maxQueries = 1, queryTimeoutMillis = 500)
        JobObservationService(port, clock, limits).use { service ->
            val stopped = service.statusForUser(call("stopped", "a"))
            await { status(remote)[1] == 1 }
            assertTrue(service.stopWaiting("s", "stopped"))
            assertTrue(stopped.get(5, TimeUnit.SECONDS) is ToolExecutorResult.CancelledWithEffectTruth)
            val expired = service.statusForUser(call("expired", "a")).get(5, TimeUnit.SECONDS)
            assertEquals("SOURCE_UNAVAILABLE", reason(expired))
            val busy = service.statusForUser(call("busy", "b")).get(5, TimeUnit.SECONDS)
            assertEquals("OBSERVATION_BUSY", reason(busy))
            assertEquals(listOf(1, 0), status(remote).drop(1))
            val revoked = service.statusForUser(call("revoked", "a"))
            current.set(false)
            transaction(remote, BlockedObservationService.RELEASE)
            val denied = revoked.get(5, TimeUnit.SECONDS)
            assertTrue("Late revoked output must not publish success", denied !is ToolExecutorResult.Completed)
            await { status(remote)[2] == 1 }
            val invalid = service.statusForUser(call("invalid", "a")).get(5, TimeUnit.SECONDS)
            assertTrue(invalid !is ToolExecutorResult.Completed)
            current.set(true)
            assertEquals("SNAPSHOT", reason(service.statusForUser(call("fresh", "b")).get(5, TimeUnit.SECONDS)))
            assertEquals(listOf(2, 2), status(remote).drop(1))
        }
    }

    private fun binding(handle: String) =
        JobObservationBinding("s", "t", handle, "binder-fixture", "e-$handle", "j-$handle", "a".repeat(64))

    private fun call(
        id: String,
        handle: String,
    ) = ExecutableToolCall(
        id,
        "jobs_status",
        "1",
        buildJsonObject { put("originalCallId", handle) },
        ExecutionTargetType.LOCAL_ANDROID,
        Instant.now().plusSeconds(10),
        NoCancellation,
        "s",
        "observer",
    )

    private fun reason(result: ToolExecutorResult): String {
        val output = (result as ToolExecutorResult.Completed).output.jsonObject
        return output.getValue("reason").jsonPrimitive.content
    }

    private fun status(remote: IBinder): List<Int> =
        transaction(remote, BlockedObservationService.STATUS) { reply -> List(3) { reply.readInt() } }

    private fun transaction(
        remote: IBinder,
        code: Int,
    ) = transaction(remote, code) { Unit }

    private fun <T> transaction(
        remote: IBinder,
        code: Int,
        read: (Parcel) -> T,
    ): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            check(remote.transact(code, data, reply, 0))
            reply.readException()
            return read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(10)
        check(condition()) { "Binder fixture condition deadline" }
    }
}
