package com.helix.app.terminal

import com.helix.app.internal.InMemoryLineStore
import com.helix.app.proot.ExecutionOwnershipStore
import com.helix.tools.framework.ExecutionOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class TerminalReclamationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val owner = ExecutionOwnership.Owner("manual", "generation")

    private fun binding(root: File) =
        ExecutionOwnershipStore(File(root, "owned.owner")).also {
            check(it.compareAndSet(null, owner))
        }

    @Test fun refusedStartEmptyHeaderReclaimsWithoutRemovingActiveIdentity() {
        val root = temporary.newFolder()
        val active = binding(root)
        val empty = ExecutionOwnershipStore(File(root, "refused.owner"))
        check(empty.compareAndSet(null, owner))
        check(empty.compareAndSet(owner, null))
        val cleanup = TerminalReclamation(InMemoryLineStore(), root)
        cleanup.discardEmpty(empty)
        cleanup.discardEmpty(empty)
        cleanup.discardEmpty(active)
        assertFalse(empty.file.exists())
        check(empty.compareAndSet(null, owner))
        check(empty.compareAndSet(owner, null))
        val interrupted = File(root, "refused.owner.reclaim")
        check(empty.file.renameTo(interrupted))
        cleanup.discardEmpty(empty)
        assertFalse(interrupted.exists())
        assertEquals(owner, active.read())
    }

    @Test fun successfulReclamationDropsOnlyItsEmptyControlFileAndRetryEntry() {
        val root = temporary.newFolder()
        val binding = binding(root)
        val agent = File(root, "agent-record.json").apply { writeText("audit") }
        val pending = InMemoryLineStore()
        val cleanup = TerminalReclamation(pending, root)
        cleanup.stage(binding, owner)
        var removed = 0
        cleanup.retry({ state, id -> check(state.compareAndSet(id, null)) }, { removed++ }, { throw it })
        assertEquals(1, removed)
        assertFalse(binding.file.exists())
        assertEquals("audit", agent.readText())
        assertTrue(pending.lines("pending").isEmpty())
        cleanup.retry({ _, _ -> error("must not repeat") }, { error("must not repeat") }, { throw it })
    }

    @Test fun inUseOrUnknownRefusalRetainsControlAndDoesNotRemoveRuntime() {
        val root = temporary.newFolder()
        val binding = binding(root)
        val pending = InMemoryLineStore()
        val cleanup = TerminalReclamation(pending, root)
        cleanup.stage(binding, owner)
        var failures = 0
        cleanup.retry({ _, _ -> error("view attached or no exit proof") }, { error("must not remove") }, { failures++ })
        assertEquals(1, failures)
        assertEquals(owner, binding.read())
        assertFalse(pending.lines("pending").isEmpty())
    }

    @Test fun partialRuntimeFailureSurvivesRestartWithoutRepeatingHostSettlement() {
        val root = temporary.newFolder()
        val binding = binding(root)
        val pending = InMemoryLineStore()
        val cleanup = TerminalReclamation(pending, root)
        cleanup.stage(binding, owner)
        cleanup.retry({ state, id -> check(state.compareAndSet(id, null)) }, { error("temporary removal failed") }, {})
        assertEquals(null, binding.read())
        assertTrue(binding.file.exists())
        var retried = false
        TerminalReclamation(pending, root).retry({ _, _ -> error("already settled") }, { retried = true }, { throw it })
        assertTrue(retried)
        assertFalse(binding.file.exists())
        assertTrue(pending.lines("pending").isEmpty())
    }

    @Test fun replacementIdentityIsNotDeletedByStaleCleanup() {
        val root = temporary.newFolder()
        val binding = binding(root)
        val pending = InMemoryLineStore()
        val cleanup = TerminalReclamation(pending, root)
        cleanup.stage(binding, owner)
        val replacement = ExecutionOwnership.Owner("new-manual", "new-generation")
        assertTrue(binding.compareAndSet(owner, replacement))
        cleanup.retry({ _, _ -> error("must not settle") }, { error("must not remove") }, {})
        assertEquals(replacement, binding.read())
    }

    @Test fun replacedControlSymlinkCannotDeleteForeignState() {
        val root = temporary.newFolder()
        val binding = binding(root)
        val pending = InMemoryLineStore()
        val cleanup = TerminalReclamation(pending, root)
        cleanup.stage(binding, owner)
        val foreign =
            temporary.newFile().apply {
                writeBytes(
                    java.nio.ByteBuffer
                        .allocate(8)
                        .putInt(1)
                        .putInt(0)
                        .array(),
                )
            }
        var failed = false
        cleanup.retry(
            { state, id -> check(state.compareAndSet(id, null)) },
            {
                java.nio.file.Files
                    .delete(binding.file.toPath())
                java.nio.file.Files
                    .createSymbolicLink(binding.file.toPath(), foreign.toPath())
            },
            { failed = true },
        )
        assertTrue(failed)
        assertTrue(foreign.exists())
        assertEquals(8L, foreign.length())
        assertFalse(pending.lines("pending").isEmpty())
    }

    @Test fun cancellationRetainsRetryAndNeverCallsRuntimeRemoval() {
        val root = temporary.newFolder()
        val binding = binding(root)
        val pending = InMemoryLineStore()
        val cleanup = TerminalReclamation(pending, root)
        cleanup.stage(binding, owner)
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            cleanup.retry(
                { _, _ -> throw java.util.concurrent.CancellationException("cancelled") },
                { error("must not remove") },
                { error("must not convert cancellation") },
            )
        }
        assertEquals(owner, binding.read())
        assertFalse(pending.lines("pending").isEmpty())
    }

    @Test fun concurrentStagingKeepsOneRetryForOriginalIdentity() {
        val root = temporary.newFolder()
        val binding = binding(root)
        val pending = InMemoryLineStore()
        val pool = Executors.newFixedThreadPool(4)
        try {
            pool
                .invokeAll(
                    (1..16).map {
                        Callable { TerminalReclamation(pending, root).stage(binding, owner) }
                    },
                ).forEach { it.get() }
            assertEquals(4, pending.lines("pending").size)
        } finally {
            pool.shutdownNow()
        }
    }
}
