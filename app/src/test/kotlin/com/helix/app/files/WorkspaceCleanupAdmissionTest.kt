package com.helix.app.files

import com.helix.tools.framework.ExecutionOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCleanupAdmissionTest {
    @Test fun liveCallAndRetainedJobBothPreventCleanupUntilSettlement() {
        val store = MemoryStore()
        val gate = ExecutionOwnership(store)
        val cleanup = WorkspaceCleanupAdmission(gate)
        var deletions = 0
        val job = ExecutionOwnership.Owner("job", "generation")
        requireNotNull(gate.acquire("launch")).use { permit ->
            assertThrows(IllegalStateException::class.java) { cleanup.run { deletions++ } }
            assertTrue(permit.retain(job))
        }
        assertThrows(IllegalStateException::class.java) { cleanup.run { deletions++ } }
        val reopened = ExecutionOwnership(store)
        assertThrows(IllegalStateException::class.java) {
            WorkspaceCleanupAdmission(reopened).run { deletions++ }
        }
        assertEquals(0, deletions)
        assertEquals(job, reopened.retainedOwner())
        assertTrue(reopened.settle(job))
        WorkspaceCleanupAdmission(reopened).run { deletions++ }
        assertEquals(1, deletions)
    }

    @Test fun cleanupExcludesNewLaunchAndReleasesAdmissionAfterFailure() {
        val gate = ExecutionOwnership(MemoryStore())
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceCleanupAdmission(gate).run {
                assertNull(gate.acquire("new-launch"))
                throw IllegalArgumentException("identity changed")
            }
        }
        requireNotNull(gate.acquire("after-failure")).close()
    }

    private class MemoryStore : ExecutionOwnership.Store {
        private var owner: ExecutionOwnership.Owner? = null

        override fun read() = owner

        override fun compareAndSet(
            expected: ExecutionOwnership.Owner?,
            replacement: ExecutionOwnership.Owner?,
        ): Boolean {
            if (owner != expected) return false
            owner = replacement
            return true
        }
    }
}
