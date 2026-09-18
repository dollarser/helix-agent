package com.helix.tools.framework

import com.helix.core.model.ExecutionTargetType
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class ExecutionOwnershipTest {
    private class MemoryStore : ExecutionOwnership.Store {
        var owner: ExecutionOwnership.Owner? = null
        var failWrite = false

        override fun read() = owner

        override fun compareAndSet(
            expected: ExecutionOwnership.Owner?,
            replacement: ExecutionOwnership.Owner?,
        ): Boolean {
            if (failWrite) throw IOException("storage unavailable")
            if (owner != expected) return false
            owner = replacement
            return true
        }
    }

    private val owner = ExecutionOwnership.Owner("execution", "generation")

    @Test fun retainedExecutionSurvivesCallCloseAndHostReconstruction() {
        val store = MemoryStore()
        val firstHost = ExecutionOwnership(store)
        requireNotNull(firstHost.acquire("start")).use { assertTrue(it.retain(owner)) }
        assertNull(firstHost.acquire("other-session-write"))
        val nextHost = ExecutionOwnership(store)
        assertEquals(owner, nextHost.retainedOwner())
        assertNull(nextHost.acquire("after-host-death"))
        assertTrue(nextHost.settle(owner))
        requireNotNull(nextHost.acquire("after-terminal-proof")).close()
    }

    @Test fun staleSettlementCannotFreeNewGeneration() {
        val store = MemoryStore()
        val gate = ExecutionOwnership(store)
        requireNotNull(gate.acquire("start")).use { assertTrue(it.retain(owner)) }
        assertFalse(gate.settle(owner.copy(generation = "older")))
        assertEquals(owner, gate.retainedOwner())
        assertTrue(gate.settle(owner))
        val replacement = owner.copy(generation = "new")
        requireNotNull(gate.acquire("next")).use { assertTrue(it.retain(replacement)) }
        assertFalse(gate.settle(owner))
        assertEquals(replacement, gate.retainedOwner())
    }

    @Test fun transferRequiresExclusiveAdmissionAndCannotReplaceAnOwner() {
        val gate = ExecutionOwnership(MemoryStore())
        requireNotNull(gate.acquire("reader", exclusive = false)).use { reader ->
            requireNotNull(gate.acquire("other-reader", exclusive = false)).use {
                assertFalse(reader.retain(owner))
            }
            assertFalse(reader.retain(owner))
            assertNull(gate.acquire("writer"))
        }
        requireNotNull(gate.acquire("first")).use { first ->
            assertNull(gate.acquire("second"))
            assertTrue(first.retain(owner))
            assertTrue(first.retain(owner))
            assertFalse(gate.settle(owner))
            assertFalse(first.retain(owner.copy(executionId = "another")))
        }
        assertNull(gate.acquire("third"))
    }

    @Test fun storageFailureDoesNotAuthorizeTransferAndFailedReleaseKeepsOwner() {
        val store = MemoryStore()
        val gate = ExecutionOwnership(store)
        requireNotNull(gate.acquire("start")).use {
            store.failWrite = true
            assertThrows(IOException::class.java) { it.retain(owner) }
            store.failWrite = false
            assertTrue(it.retain(owner))
        }
        store.failWrite = true
        assertThrows(IOException::class.java) { gate.settle(owner) }
        assertNull(gate.acquire("blocked"))
    }

    @Test fun ordinaryCloseIsIdempotentAndClosedPermitCannotTransfer() {
        val gate = ExecutionOwnership(MemoryStore())
        val permit = requireNotNull(gate.acquire("ordinary"))
        assertThrows(IllegalStateException::class.java) { gate.acquire("ordinary") }
        permit.close()
        permit.close()
        assertThrows(IllegalStateException::class.java) { permit.retain(owner) }
        assertNotNull(gate.acquire("ordinary"))
    }

    @Test fun guardedExecutorFailureReleasesOrdinaryAdmission() {
        val gate = ExecutionOwnership(MemoryStore())
        val implementation =
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult = throw IOException("failed")
            }
        val executor = gate.guard(implementation)
        assertThrows(IOException::class.java) { executor.execute(call()) }
        requireNotNull(gate.acquire("call")).close()
    }

    @Test fun launchingExecutorTransfersAdmissionBeforeReturning() {
        val gate = ExecutionOwnership(MemoryStore())
        val implementation =
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    assertTrue(gate.retainForCall(call.toolCallId, owner))
                    assertFalse(gate.settle(owner))
                    return ToolExecutorResult.Completed(buildJsonObject {})
                }
            }
        assertTrue(gate.guard(implementation).execute(call()) is ToolExecutorResult.Completed)
        assertEquals(owner, gate.retainedOwner())
        assertNull(gate.acquire("next-call"))
        assertTrue(gate.settle(owner))
    }

    @Test fun blockedAndCancelledCallsNeverEnterExecutor() {
        val gate = ExecutionOwnership(MemoryStore())
        requireNotNull(gate.acquire("start")).use { assertTrue(it.retain(owner)) }
        var executed = false
        val implementation =
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    executed = true
                    return ToolExecutorResult.Completed(buildJsonObject {})
                }
            }
        val executor = gate.guard(implementation)
        val blocked = executor.execute(call()) as ToolExecutorResult.Failed
        assertTrue(blocked.sideEffectFree)
        val cancellation =
            object : CancelSignal {
                override fun isCancelled() = true
            }
        assertEquals(ToolExecutorResult.Cancelled, executor.execute(call().copy(cancel = cancellation)))
        assertFalse(executed)
    }

    private fun call() =
        ExecutableToolCall(
            toolCallId = "call",
            toolName = "write",
            toolVersion = "1",
            args = buildJsonObject {},
            executionTarget = ExecutionTargetType.LOCAL_PROOT,
            deadline = Instant.MAX,
            cancel = NoCancellation,
        )
}
