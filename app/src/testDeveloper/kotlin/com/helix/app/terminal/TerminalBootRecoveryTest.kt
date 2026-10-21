package com.helix.app.terminal

import com.helix.app.internal.InMemoryLineStore
import com.helix.tools.framework.ExecutionOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalBootRecoveryTest {
    private val owner = ExecutionOwnership.Owner("old", "generation")

    @Test fun missingJournalRequiresStrictlyLaterBootAndSurvivesHostRestart() {
        val store = InMemoryLineStore()
        assertFalse(TerminalBootRecovery(store).observe(owner, 10))
        val reopened = TerminalBootRecovery(store)
        assertFalse(reopened.observe(owner, 10))
        assertFalse(reopened.observe(owner, 9))
        assertFalse(reopened.observe(owner, null))
        assertTrue(reopened.observe(owner, 11))
        assertFalse(reopened.observe(owner.copy(generation = "replacement"), 11))
    }

    @Test fun laterBootReleasesOnlyExactIdentityAndKeepsRecoveryReceipt() {
        val store = InMemoryLineStore()
        val recovery = TerminalBootRecovery(store)
        recovery.observe(owner, 10)
        val binding = Store(setOf(owner))
        val sibling = ExecutionOwnership.Owner("sibling", "generation")
        val registry = Store(setOf(owner, sibling))
        recovery.settle(owner, 11, ExecutionOwnership(registry), binding)
        assertNull(binding.read())
        assertEquals(setOf(sibling), registry.owners())
        assertTrue(TerminalBootRecovery(store).observe(owner, 11))
        assertEquals(listOf("old", "generation", "10", "11"), store.lines("missing-terminal-old-generation"))
    }

    @Test fun sameBootCannotReleaseOrForgetUnknownProcess() {
        val recovery = TerminalBootRecovery(InMemoryLineStore())
        val binding = Store(setOf(owner))
        val registry = Store(setOf(owner))
        recovery.observe(owner, 10)
        assertThrows(
            IllegalStateException::class.java,
        ) { recovery.settle(owner, 10, ExecutionOwnership(registry), binding) }
        assertEquals(setOf(owner), binding.owners())
        assertEquals(setOf(owner), registry.owners())
    }

    @Test fun changedBindingCannotBeClearedUsingAnOldBootReceipt() {
        val recovery = TerminalBootRecovery(InMemoryLineStore())
        recovery.observe(owner, 10)
        val replacement = owner.copy(generation = "new")
        val binding = Store(setOf(replacement))
        val registry = Store(setOf(owner, replacement))
        assertThrows(
            IllegalStateException::class.java,
        ) { recovery.settle(owner, 11, ExecutionOwnership(registry), binding) }
        assertEquals(setOf(replacement), binding.owners())
        assertEquals(setOf(owner, replacement), registry.owners())
    }

    private class Store(
        private var entries: Set<ExecutionOwnership.Owner>,
    ) : ExecutionOwnership.Store {
        override fun owners() = entries

        override fun update(
            expected: Set<ExecutionOwnership.Owner>,
            replacement: Set<ExecutionOwnership.Owner>,
        ): Boolean {
            if (entries != expected) return false
            entries = replacement
            return true
        }
    }
}
