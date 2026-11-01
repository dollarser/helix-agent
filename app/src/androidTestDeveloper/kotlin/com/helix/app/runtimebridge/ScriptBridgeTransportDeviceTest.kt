package com.helix.app.runtimebridge

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.core.model.ToolDispatchOutcome
import com.helix.tools.framework.ExecutableToolCall
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Kernel peer/EOF/revocation tests have no user files, real tool actions or permission grants. */
class ScriptBridgeTransportDeviceTest {
    private fun parent() =
        ExecutableToolCall(
            "fixture",
            "code.linux.run",
            "1",
            buildJsonObject {},
            ExecutionTargetType.LOCAL_PROOT,
            Instant.now().plusSeconds(20),
            NoCancellation,
            "fixture",
            "fixture",
        )

    @Test fun badProofDuplicateAndRevocationCannotDispatchOrReplay() {
        val count = AtomicInteger()
        val lease =
            ScriptBridgeSession(parent(), { true }, { _, _, _, _, _ ->
                count.incrementAndGet()
                ToolDispatchOutcome.Cancelled
            }, { _, _, _ -> false }, { false })
        lease.bindPeer(Process.myPid(), Process.myUid())
        try {
            assertFalse(lease.endpoint.toString().contains(lease.endpoint.proof))
            val valid = """{"proof":"${lease.endpoint.proof}","id":"same","tool":"time.now","args":{}}"""
            assertTrue(exchange(lease, valid.replace(lease.endpoint.proof, "wrong")).contains("REFUSED"))
            assertEquals(0, count.get())
            assertTrue(exchange(lease, valid).contains("CANCELLED"))
            assertTrue(exchange(lease, valid).contains("ALREADY_SEEN"))
            assertEquals(1, count.get())
        } finally {
            lease.close()
        }
        assertFalse(lease.acceptsPeer(Process.myPid(), Process.myUid()))
    }

    @Test fun clientDisconnectCancelsOriginalAttemptWithoutReleasingItsPhysicalExecutionEarly() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val lease =
            ScriptBridgeSession(parent(), { true }, { _, _, _, _, cancel ->
                started.countDown()
                while (!cancel.isCancelled()) Thread.sleep(10)
                cancelled.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                exited.countDown()
                ToolDispatchOutcome.Cancelled
            }, { _, _, _ -> false }, { false })
        lease.bindPeer(Process.myPid(), Process.myUid())
        try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(lease.endpoint.socketName))
                val request = """{"proof":"${lease.endpoint.proof}","id":"one","tool":"time.now","args":{}}"""
                socket.outputStream.write((request + "\n").toByteArray())
                socket.outputStream.flush()
                assertTrue(started.await(5, TimeUnit.SECONDS))
            }
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertEquals(1L, exited.count)
            release.countDown()
            assertTrue(exited.await(5, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            lease.close()
        }
    }

    private fun exchange(
        lease: ScriptBridgeSession,
        request: String,
    ): String =
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(lease.endpoint.socketName))
            socket.soTimeout = 5_000
            socket.outputStream.write((request + "\n").toByteArray())
            socket.outputStream.flush()
            requireNotNull(socket.inputStream.bufferedReader().readLine())
        }
}
