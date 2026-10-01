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
        var owner: Set<ExecutionOwnership.Owner> = emptySet()
        var failWrite = false

        override fun owners() = owner

        override fun update(
            expected: Set<ExecutionOwnership.Owner>,
            replacement: Set<ExecutionOwnership.Owner>,
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
        requireNotNull(firstHost.acquire("other-session-write")).close()
        val nextHost = ExecutionOwnership(store)
        assertEquals(owner, nextHost.retainedOwners().singleOrNull())
        requireNotNull(nextHost.acquire("after-host-death")).close()
        assertTrue(nextHost.settle(owner))
        requireNotNull(nextHost.acquire("after-terminal-proof")).close()
    }

    @Test fun staleSettlementCannotFreeNewGeneration() {
        val store = MemoryStore()
        val gate = ExecutionOwnership(store)
        requireNotNull(gate.acquire("start")).use { assertTrue(it.retain(owner)) }
        assertFalse(gate.settle(owner.copy(generation = "older")))
        assertEquals(owner, gate.retainedOwners().singleOrNull())
        assertTrue(gate.settle(owner))
        val replacement = owner.copy(generation = "new")
        requireNotNull(gate.acquire("next")).use { assertTrue(it.retain(replacement)) }
        assertFalse(gate.settle(owner))
        assertEquals(replacement, gate.retainedOwners().singleOrNull())
    }

    @Test fun independentLaunchesRetainTheirOwnIdentitiesWithoutExcludingWriters() {
        val gate = ExecutionOwnership(MemoryStore())
        val other = owner.copy(executionId = "other")
        requireNotNull(gate.acquire("first")).use { first ->
            requireNotNull(gate.acquire("second")).use { second ->
                assertTrue(first.retain(owner))
                assertTrue(first.retain(owner))
                assertTrue(second.retain(other))
                assertFalse(first.retain(other))
                assertFalse(gate.settle(owner))
                requireNotNull(gate.acquire("writer")).close()
            }
        }
        assertEquals(setOf(owner, other), gate.retainedOwners())
        assertTrue(gate.settle(owner))
        assertEquals(setOf(other), gate.retainedOwners())
        requireNotNull(gate.acquire("third")).close()
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
        requireNotNull(gate.acquire("blocked")).close()
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
        assertEquals(owner, gate.retainedOwners().singleOrNull())
        requireNotNull(gate.acquire("next-call")).close()
        assertTrue(gate.settle(owner))
    }

    @Test fun capacityExhaustedAndCancelledCallsNeverEnterExecutor() {
        val gate = ExecutionOwnership(MemoryStore())
        val occupied = List(32) { requireNotNull(gate.acquire("occupied-$it")) }
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
        occupied.forEach { it.close() }
    }

    @Test fun reconciliationCannotRaceLauncherOrUseAnotherGeneration() {
        val gate = ExecutionOwnership(MemoryStore())
        requireNotNull(gate.acquire("start")).use {
            assertTrue(it.retain(owner))
            assertNull(gate.acquireReconciliation(owner))
        }
        assertNull(gate.acquireReconciliation(owner.copy(generation = "stale")))
        requireNotNull(gate.acquireReconciliation(owner)).use {
            assertNull(gate.acquireReconciliation(owner))
            assertFalse(gate.settle(owner))
            requireNotNull(gate.acquire("writer")).close()
        }
        assertEquals(owner, gate.retainedOwners().singleOrNull())
    }

    @Test fun settlementProtectsOnlyOriginalControlUntilImporterExits() {
        val gate = ExecutionOwnership(MemoryStore())
        requireNotNull(gate.acquire("start")).use { assertTrue(it.retain(owner)) }
        val reconciliation = requireNotNull(gate.acquireReconciliation(owner))
        assertTrue(reconciliation.settle())
        assertNull(gate.retainedOwners().singleOrNull())
        val sameControl = gate.controlExecutor({ owner }) { _, _ -> error("duplicate importer") }
        assertTrue(gate.guard(sameControl).execute(call()) is ToolExecutorResult.Failed)
        requireNotNull(gate.acquire("racing-writer")).close()
        reconciliation.close()
        reconciliation.close()
        assertThrows(IllegalStateException::class.java) { reconciliation.settle() }
        requireNotNull(gate.acquire("next-writer")).close()
    }

    @Test fun failedResultSettlementKeepsDurableOwnerForRetry() {
        val store = MemoryStore()
        val gate = ExecutionOwnership(store)
        requireNotNull(gate.acquire("start")).use { assertTrue(it.retain(owner)) }
        requireNotNull(gate.acquireReconciliation(owner)).use {
            store.failWrite = true
            assertThrows(IOException::class.java) { it.settle() }
        }
        requireNotNull(gate.acquire("writer")).close()
        store.failWrite = false
        requireNotNull(gate.acquireReconciliation(owner)).use { assertTrue(it.settle()) }
        requireNotNull(gate.acquire("writer")).close()
    }

    @Test fun boundControlKeepsOriginalOwnerUntilExplicitSettlement() {
        val gate = ExecutionOwnership(MemoryStore())
        requireNotNull(gate.acquire("start")).use { it.retain(owner) }
        val control =
            gate.controlExecutor({ owner }) { _, permit ->
                requireNotNull(gate.acquire("writer")).close()
                assertTrue(requireNotNull(permit).settle())
                requireNotNull(gate.acquire("writer-after-settlement")).close()
                ToolExecutorResult.Completed(buildJsonObject {})
            }
        assertTrue(control.execute(call()) is ToolExecutorResult.Failed)
        assertEquals(owner, gate.retainedOwners().singleOrNull())
        assertTrue(gate.guard(control).execute(call()) is ToolExecutorResult.Completed)
        requireNotNull(gate.acquire("writer")).close()
    }

    @Test fun foreignControlNeverRunsAndQueryFailureKeepsOriginalOwner() {
        val gate = ExecutionOwnership(MemoryStore())
        requireNotNull(gate.acquire("start")).use { it.retain(owner) }
        val foreign =
            gate.controlExecutor({ owner.copy(generation = "foreign") }) { _, _ ->
                error("foreign control executed")
            }
        assertTrue(gate.guard(foreign).execute(call()) is ToolExecutorResult.Failed)
        val failed = gate.controlExecutor({ owner }) { _, _ -> throw IOException("IPC unavailable") }
        assertThrows(IOException::class.java) { gate.guard(failed).execute(call()) }
        assertEquals(owner, gate.retainedOwners().singleOrNull())
        requireNotNull(gate.acquireReconciliation(owner)).close()
    }

    @Test fun alreadySettledControlDoesNotBlockUnrelatedExecution() {
        val gate = ExecutionOwnership(MemoryStore())
        val control =
            gate.controlExecutor({ owner }) { _, permit ->
                assertNull(permit)
                requireNotNull(gate.acquire("writer")).close()
                ToolExecutorResult.Completed(buildJsonObject {})
            }
        assertTrue(gate.guard(control).execute(call()) is ToolExecutorResult.Completed)
        requireNotNull(gate.acquire("writer")).close()
    }

    @Test fun aControlCannotRunThroughAnotherHostOrAfterCancellation() {
        val gate = ExecutionOwnership(MemoryStore())
        val otherHost = ExecutionOwnership(MemoryStore())
        val control = gate.controlExecutor({ owner }) { _, _ -> error("control entered") }
        assertThrows(IllegalStateException::class.java) { otherHost.guard(control).execute(call()) }
        val cancelled =
            call().copy(
                cancel =
                    object : CancelSignal {
                        override fun isCancelled() = true
                    },
            )
        assertEquals(ToolExecutorResult.Cancelled, gate.guard(control).execute(cancelled))
    }

    @Test fun provenNoStartReleasesOnlyTheOriginalLiveLauncher() {
        val gate = ExecutionOwnership(MemoryStore())
        requireNotNull(gate.acquire("start")).use {
            assertTrue(it.retain(owner))
            assertFalse(gate.releaseUnsubmittedForCall("start", owner.copy(generation = "foreign")))
            assertThrows(IllegalStateException::class.java) { gate.releaseUnsubmittedForCall("other", owner) }
            assertTrue(gate.releaseUnsubmittedForCall("start", owner))
            requireNotNull(gate.acquire("writer")).close()
        }
        requireNotNull(gate.acquire("writer")).close()
        assertThrows(IllegalStateException::class.java) { gate.releaseUnsubmittedForCall("start", owner) }
    }

    @Test fun explicitMetadataExecutorLeavesRetainedOwnershipUntouched() {
        val gate = ExecutionOwnership(MemoryStore())
        requireNotNull(gate.acquire("start")).use { assertTrue(it.retain(owner)) }
        var calls = 0
        val implementation =
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                    calls++
                    return ToolExecutorResult.Completed(buildJsonObject {})
                }
            }
        val metadata = gate.metadataExecutor(implementation)
        val bound = call().copy(sessionId = "session", turnId = "turn")
        assertTrue(metadata.execute(bound) is ToolExecutorResult.Failed)
        assertTrue(gate.guard(metadata).execute(bound) is ToolExecutorResult.Completed)
        requireNotNull(gate.acquireReconciliation(owner)).use {
            assertTrue(gate.guard(metadata).execute(bound) is ToolExecutorResult.Completed)
        }
        assertEquals(2, calls)
        assertEquals(owner, gate.retainedOwners().singleOrNull())
        requireNotNull(gate.acquire("ordinary-write")).close()
        assertTrue(
            gate.guard(implementation).execute(bound.copy(toolName = "goal.report")) is ToolExecutorResult.Completed,
        )
    }

    @Test fun metadataWrapperRequiresItsHostBindingAndCancellationChecks() {
        val gate = ExecutionOwnership(MemoryStore())
        var calls = 0
        val metadata =
            gate.metadataExecutor(
                object : ToolExecutor {
                    override fun execute(call: ExecutableToolCall): ToolExecutorResult {
                        calls++
                        return ToolExecutorResult.Completed(buildJsonObject {})
                    }
                },
            )
        assertTrue(gate.guard(metadata).execute(call()) is ToolExecutorResult.Failed)
        val bound = call().copy(sessionId = "session", turnId = "turn")
        assertThrows(
            IllegalStateException::class.java,
        ) { ExecutionOwnership(MemoryStore()).guard(metadata).execute(bound) }
        val cancelled =
            bound.copy(
                cancel =
                    object : CancelSignal {
                        override fun isCancelled() = true
                    },
            )
        assertEquals(ToolExecutorResult.Cancelled, gate.guard(metadata).execute(cancelled))
        assertEquals(0, calls)
    }

    @Test
    fun transferRetainedAtomicallySwapsOwnerWhenMatchingAndExcludesActiveAdmission() {
        val store = MemoryStore()
        val gate = ExecutionOwnership(store)
        requireNotNull(gate.acquire("launch")).use {
            assertTrue(it.retain(owner))
        }
        assertEquals(owner, gate.retainedOwners().singleOrNull())

        val replacement = ExecutionOwnership.Owner("second-exec", "second-gen")
        val wrongOwner = ExecutionOwnership.Owner("wrong-exec", "wrong-gen")

        // Mismatch fails
        assertFalse(gate.transferRetained(wrongOwner, replacement))
        assertEquals(owner, gate.retainedOwners().singleOrNull())

        // Reconciling admission prevents transfer
        requireNotNull(gate.acquireReconciliation(owner)).use {
            assertFalse(gate.transferRetained(owner, replacement))
        }

        // Matching owner with no active admission succeeds
        assertTrue(gate.transferRetained(owner, replacement))
        assertEquals(replacement, gate.retainedOwners().singleOrNull())
        requireNotNull(gate.acquire("writer")).close()

        // Storage failure propagates
        store.failWrite = true
        assertThrows(IOException::class.java) {
            gate.transferRetained(replacement, owner)
        }
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
