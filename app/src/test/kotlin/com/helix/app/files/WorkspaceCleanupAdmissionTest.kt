package com.helix.app.files

import com.helix.tools.framework.ExecutionOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCleanupAdmissionTest {
    @Test fun explicitWorkspaceCleanupDoesNotLockUnrelatedExecutions() {
        val store = MemoryStore()
        val gate = ExecutionOwnership(store)
        val cleanup = WorkspaceCleanupAdmission(gate)
        var deletions = 0
        val job = ExecutionOwnership.Owner("job", "generation")
        requireNotNull(gate.acquire("launch")).use { permit ->
            cleanup.run { deletions++ }
            assertTrue(permit.retain(job))
        }
        cleanup.run { deletions++ }
        val reopened = ExecutionOwnership(store)
        WorkspaceCleanupAdmission(reopened).run { deletions++ }
        assertEquals(3, deletions)
        assertEquals(job, reopened.retainedOwners().singleOrNull())
        assertTrue(reopened.settle(job))
        WorkspaceCleanupAdmission(reopened).run { deletions++ }
        assertEquals(4, deletions)
    }

    @Test fun cleanupAllowsNewLaunchAndReleasesItsOwnAdmissionAfterFailure() {
        val gate = ExecutionOwnership(MemoryStore())
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceCleanupAdmission(gate).run {
                requireNotNull(gate.acquire("new-launch")).close()
                throw IllegalArgumentException("identity changed")
            }
        }
        requireNotNull(gate.acquire("after-failure")).close()
    }

    private class MemoryStore : ExecutionOwnership.Store {
        private var owner: Set<ExecutionOwnership.Owner> = emptySet()

        override fun owners() = owner

        override fun update(
            expected: Set<ExecutionOwnership.Owner>,
            replacement: Set<ExecutionOwnership.Owner>,
        ): Boolean {
            if (owner != expected) return false
            owner = replacement
            return true
        }
    }
}
