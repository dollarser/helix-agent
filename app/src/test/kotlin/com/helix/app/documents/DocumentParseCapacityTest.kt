package com.helix.app.documents

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.extensions.documents.DocumentException
import com.helix.tools.framework.ExecutableToolCall
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DocumentParseCapacityTest {
    private fun call() =
        ExecutableToolCall(
            "c",
            "documents.read",
            "1",
            JsonObject(emptyMap()),
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(1),
            NoCancellation,
        )

    @Test fun hostExitDoesNotReleaseAnActiveNativeParser() {
        val capacity = DocumentParseCapacity()
        val lease = capacity.acquire(call())
        lease.beginNative()
        lease.finishHost()
        assertTrue(lease.awaitingExit)
        assertFalse(lease.awaitExit(1, java.util.concurrent.TimeUnit.MILLISECONDS))
        assertEquals(
            "TIMED_OUT",
            assertThrows(DocumentException::class.java) {
                capacity.acquire(call().copy(deadline = Instant.now().plusMillis(40)))
            }.code,
        )
        lease.nativeExited()
        assertFalse(lease.awaitingExit)
        assertTrue(lease.awaitExit(10, java.util.concurrent.TimeUnit.MILLISECONDS))
        capacity.acquire(call()).finishHost()
    }

    @Test fun repeatedDeathAndLateHostCleanupCannotReleaseAnotherParsersSlot() {
        val capacity = DocumentParseCapacity()
        val old = capacity.acquire(call())
        old.beginNative()
        old.nativeExited()
        val replacement = capacity.acquire(call())
        replacement.beginNative()
        old.nativeExited()
        old.finishHost()
        assertEquals(
            "TIMED_OUT",
            assertThrows(DocumentException::class.java) {
                capacity.acquire(call().copy(deadline = Instant.now().plusMillis(40)))
            }.code,
        )
        replacement.nativeExited()
        capacity.acquire(call()).finishHost()
    }

    @Test fun cancelledQueueAndFailedPreparationDoNotConsumeCapacity() {
        val capacity = DocumentParseCapacity()
        assertEquals(
            "CANCELLED",
            assertThrows(DocumentException::class.java) {
                capacity.acquire(
                    call().copy(
                        cancel =
                            object : com.helix.core.model.CancelSignal {
                                override fun isCancelled() = true
                            },
                    ),
                )
            }.code,
        )
        val lease = capacity.acquire(call())
        lease.finishHost()
        lease.finishHost()
        val next = capacity.acquire(call())
        next.finishHost()
        assertEquals("PARSER_DIED", assertThrows(DocumentException::class.java) { next.beginNative() }.code)
    }
}
