package com.helix.app.terminal

import com.helix.runtime.proot.core.PtyProcessIdentity
import com.helix.runtime.proot.core.PtySessionOrigin
import com.helix.runtime.proot.core.PtySessionRecord
import com.helix.tools.framework.ExecutionOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ManualTerminalBindingsTest {
    private class Store(
        var value: ExecutionOwnership.Owner? = null,
    ) : ExecutionOwnership.Store {
        override fun owners() = setOfNotNull(value)

        override fun update(
            expected: Set<ExecutionOwnership.Owner>,
            replacement: Set<ExecutionOwnership.Owner>,
        ): Boolean {
            if (owners() != expected) return false
            value = replacement.singleOrNull()
            return true
        }
    }

    private val owner = ExecutionOwnership.Owner("terminal", "generation")
    private val record =
        PtySessionRecord(
            PtySessionOrigin("terminal", "generation", "terminal", "/workspace", "r1", 7, 10_000, 1000, 61_000),
        ).started(PtyProcessIdentity(123, 42))

    @Test fun fourSlotsPreserveExistingFirstTwoAndAllRetainedHigherSlots() {
        val stores = List(4) { Store() }
        stores[0].value = owner
        val bindings = ManualTerminalBindings(stores)
        assertSame(stores[1], bindings.free(4))
        stores[3].value = ExecutionOwnership.Owner("fourth", "g4")
        assertEquals(2, bindings.owners().size)
        assertEquals("fourth", bindings.find("fourth").second.executionId)
        assertThrows(IllegalStateException::class.java) { bindings.free(1) }
        assertEquals(owner, stores[0].value)
        assertEquals("fourth", stores[3].value?.executionId)
    }

    @Test fun duplicateBindingOnlyClearsTheDuplicateWithExactCas() {
        val first = Store(owner)
        val second = Store(owner)
        assertEquals(1, ManualTerminalBindings(listOf(first, second)).owners().size)
        assertEquals(owner, first.value)
        assertNull(second.value)
    }

    @Test fun unknownOrMissingExitNeverAcksOrReleasesIdentity() {
        val binding = Store(owner)
        val retained = Store(owner)
        val ownership = ExecutionOwnership(retained)
        for (uncertain in listOf(record, record.runtimeLost(), null)) {
            assertThrows(IllegalStateException::class.java) {
                settleTerminalBinding(ownership, binding, owner, { uncertain }, { error("must not ACK") })
            }
            assertEquals(owner, binding.value)
            assertEquals(owner, retained.value)
        }
    }

    @Test fun provenExitIsAcknowledgedThenReleasesOnlyOriginalBinding() {
        val binding = Store(owner)
        val retained = Store(owner)
        val ownership = ExecutionOwnership(retained)
        val stopped = record.stoppedTree(0)
        var acked = false
        settleTerminalBinding(ownership, binding, owner, { stopped }) {
            assertEquals(owner, binding.value)
            assertEquals(owner, retained.value)
            acked = true
            stopped.acknowledge()
        }
        assertEquals(true, acked)
        assertNull(binding.value)
        assertFalse(ownership.isRetained(owner))
        assertSame(binding, ManualTerminalBindings(listOf(binding)).free(1))
    }

    @Test fun staleAckAndChangedBindingDoNotClearReplacement() {
        val binding = Store(owner)
        val retained = Store(owner)
        val ownership = ExecutionOwnership(retained)
        val stopped = record.stoppedTree(0)
        assertThrows(IllegalStateException::class.java) {
            settleTerminalBinding(ownership, binding, owner, { stopped }, { stopped })
        }
        assertEquals(owner, retained.value)
        val replacement = ExecutionOwnership.Owner("replacement", "next")
        assertThrows(IllegalStateException::class.java) {
            settleTerminalBinding(ownership, binding, owner, { stopped }) {
                binding.value = replacement
                stopped.acknowledge()
            }
        }
        assertEquals(replacement, binding.value)
    }
}
