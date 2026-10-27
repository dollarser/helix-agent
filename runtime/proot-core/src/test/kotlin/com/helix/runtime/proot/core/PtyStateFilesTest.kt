package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class PtyStateFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun original(id: String = "manual") =
        PtySessionRecord(
            PtySessionOrigin(id, "generation", id, "/workspace", "runtime", 7, 100, 10, 1000),
        )

    private fun store(
        root: File,
        stopped: PtySessionRecord,
    ): PtySessionStore {
        val store = PtySessionStore(File(root, "terminal-sessions"))
        val first = PtySessionRecord(stopped.origin)
        check(store.compareAndSet(null, first))
        val running = first.started(PtyProcessIdentity(123, 456))
        check(store.compareAndSet(first, running))
        val proof = stopped.copy(reconciled = false)
        check(store.compareAndSet(running, proof))
        if (stopped.reconciled) check(store.compareAndSet(proof, stopped))
        return store
    }

    @Test fun endedManualStateReclaimsOnlyItsTemporaryTreeAndJournal() {
        val root = temporary.newFolder()
        val runtime = File(root, "runtime")
        val owned = File(runtime, "inst_current/tmp/pty-manual").apply { mkdirs() }
        File(owned, "cache").writeText("temporary")
        val keep =
            listOf(
                "runtime/inst_current/home/.ash_history",
                "workspaces/app/terminal/code.py",
                "runtime/jobs/agent/record.json",
            ).map {
                File(root, it).apply {
                    parentFile.mkdirs()
                    writeText("retain")
                }
            }
        val ended = original().started(PtyProcessIdentity(123, 456)).stoppedTree(0).acknowledge()
        val store = store(root, ended)
        assertTrue(PtyStateFiles.reclaim(runtime, store, ended, false))
        assertFalse(owned.exists())
        assertEquals(null, store.read("manual"))
        keep.forEach { assertEquals("retain", it.readText()) }
        assertTrue(PtyStateFiles.reclaim(runtime, store, ended, false))
    }

    @Test fun activeUnknownAndUnacknowledgedStatesAreNeverDeleted() {
        val root = temporary.newFolder()
        val running = original().started(PtyProcessIdentity(123, 456))
        val stopped = running.stoppedTree(0)
        val store = store(root, stopped)
        for (record in listOf(running, running.runtimeLost(), stopped)) {
            assertThrows(IllegalArgumentException::class.java) {
                PtyStateFiles.reclaim(File(root, "runtime"), store, record, false)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PtyStateFiles.reclaim(File(root, "runtime"), store, stopped.acknowledge(), true)
        }
        assertEquals(stopped, store.read("manual"))
    }

    @Test fun replacementProofReclaimsWithoutInventingCommandExitStatus() {
        val root = temporary.newFolder()
        val stopped =
            original()
                .started(
                    PtyProcessIdentity(123, 456),
                ).runtimeLost()
                .afterPackageReplacement(PtyPackageReplacement(7, 200, 201, 300, 210))
                .acknowledge()
        val store = store(root, stopped)
        assertTrue(PtyStateFiles.reclaim(File(root, "runtime"), store, stopped, false))
        assertEquals(null, stopped.exitStatus)
    }

    @Test fun symlinkInsideTemporaryDirectoryNeverReachesWorkspace() {
        val root = temporary.newFolder()
        val outside = File(root, "workspace").apply { mkdirs() }
        File(outside, "keep").writeText("user")
        val owned = File(root, "runtime/inst_current/tmp/pty-manual").apply { mkdirs() }
        Files.createSymbolicLink(File(owned, "link").toPath(), outside.toPath())
        PtyStateFiles.removeTemporary(File(root, "runtime"), "manual")
        assertEquals("user", File(outside, "keep").readText())
        assertFalse(owned.exists())
    }

    @Test fun symlinkAncestorAndChangedMetadataFailWithoutDeletingForeignContent() {
        val root = temporary.newFolder()
        val outside = temporary.newFolder()
        File(outside, "keep").writeText("user")
        Files.createSymbolicLink(File(root, "inst_current").toPath(), outside.toPath())
        assertThrows(Exception::class.java) { PtyStateFiles.removeTemporary(root, "manual") }
        assertEquals("user", File(outside, "keep").readText())
        val metadata = File(root, "record.pty").apply { writeText("replacement") }
        assertFalse(PtyStateFiles.removeMetadata(root, "record.pty", "old".toByteArray()))
        assertEquals("replacement", metadata.readText())
    }

    @Test fun failedTemporaryCleanupKeepsProofForRetry() {
        val root = temporary.newFolder()
        val runtime = File(root, "runtime").apply { mkdirs() }
        val outside = temporary.newFolder()
        File(outside, "user").writeText("keep")
        val link = File(runtime, "inst_current")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val ended = original().started(PtyProcessIdentity(123, 456)).stoppedTree(0).acknowledge()
        val store = store(root, ended)
        assertThrows(Exception::class.java) { PtyStateFiles.reclaim(runtime, store, ended, false) }
        assertEquals(ended, store.read("manual"))
        assertEquals("keep", File(outside, "user").readText())
        Files.delete(link.toPath())
        File(link, "tmp/pty-manual").mkdirs()
        assertTrue(PtyStateFiles.reclaim(runtime, store, ended, false))
    }

    @Test fun concurrentIndependentReclaimsLeaveOtherNamespacesAlone() {
        val root = temporary.newFolder()
        val runtime = File(root, "runtime")
        val records =
            listOf("one", "two").map {
                val owned = File(runtime, "inst_current/tmp/pty-$it").apply { mkdirs() }
                File(owned, "scratch").writeText("temporary")
                original(it).started(PtyProcessIdentity(123, 456)).stoppedTree(0).acknowledge()
            }
        val first = store(root, records[0])
        store(root, records[1])
        val active = File(runtime, "inst_current/tmp/pty-active").apply { mkdirs() }
        File(active, "keep").writeText("active")
        val pool =
            java.util.concurrent.Executors
                .newFixedThreadPool(2)
        try {
            val results =
                pool
                    .invokeAll(
                        records.map { record ->
                            java.util.concurrent.Callable { PtyStateFiles.reclaim(runtime, first, record, false) }
                        },
                    ).map { it.get() }
            assertTrue(results.all { it })
        } finally {
            pool.shutdownNow()
        }
        assertEquals("active", File(active, "keep").readText())
    }

    @Test fun interruptedMetadataRemovalResumesFromExactQuarantinedReceipt() {
        val root = temporary.newFolder()
        val ended = original().started(PtyProcessIdentity(123, 456)).stoppedTree(0).acknowledge()
        val store = store(root, ended)
        val directory = File(root, "terminal-sessions")
        val primary = File(directory, "manual.pty")
        val staged = File(directory, "manual.pty.reclaim")
        Files.move(primary.toPath(), staged.toPath())
        assertEquals(ended, store.read("manual"))
        assertTrue(PtyStateFiles.reclaim(File(root, "runtime"), store, ended, false))
        assertFalse(staged.exists())
        val control = File(root, "empty.owner.reclaim").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertTrue(PtyStateFiles.removeMetadata(root, "empty.owner", byteArrayOf(1, 2, 3)))
        assertFalse(control.exists())
    }

    @Test fun anotherGenerationWithSamePidCannotBeReclaimedByOldReceipt() {
        val root = temporary.newFolder()
        val ended = original().started(PtyProcessIdentity(123, 456)).stoppedTree(0).acknowledge()
        val store = store(root, ended)
        val stale = ended.copy(origin = ended.origin.copy(generation = "older"), process = PtyProcessIdentity(123, 123))
        assertFalse(PtyStateFiles.reclaim(File(root, "runtime"), store, stale, false))
        assertEquals(ended, store.read("manual"))
    }
}
