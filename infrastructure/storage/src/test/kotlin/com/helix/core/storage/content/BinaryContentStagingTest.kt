package com.helix.core.storage.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class BinaryContentStagingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun callerOwnsBinaryStreamAndCorruptExistingBytesAreRejected() {
        val store = FileContentStore(temporary.newFolder())
        val closed = AtomicBoolean()
        val stream =
            object : ByteArrayInputStream(BYTES) {
                override fun close() {
                    closed.set(true)
                }
            }
        val ref = store.writeBinary(stream, 100) { }
        assertFalse(closed.get())
        val target = File(store.root, ref.relativePath)
        target.writeBytes(ByteArray(BYTES.size))
        assertThrows(IllegalArgumentException::class.java) { store.writeBinary(BYTES.inputStream(), 100) { } }
        assertTrue(ByteArray(BYTES.size).contentEquals(target.readBytes()))
        assertFalse(store.root.walkTopDown().any { it.name.startsWith("binary.tmp-") })
    }

    @Test fun reuseStagingDeletionFailureCannotCommitAReference() {
        val store = FileContentStore(temporary.newFolder())
        val ref = store.writeBinary(BYTES.inputStream(), 100) { }
        val committed = AtomicBoolean()
        store.stageBinary().use { stage ->
            stage.copyFrom(BYTES.inputStream(), 100) { }
            // Deterministic private-file deletion failure, independent of the host user's UID.
            assertTrue(stage.file.delete())
            assertTrue(stage.file.mkdir())
            val obstacle = File(stage.file, "occupied")
            obstacle.writeText("private fixture")
            try {
                assertThrows(IllegalStateException::class.java) {
                    stage.publish({ }) { committed.set(true) }
                }
                assertFalse(committed.get())
                assertTrue(BYTES.contentEquals(store.verifiedBinary(ref, 100) { }.readBytes()))
            } finally {
                assertTrue(obstacle.delete())
                assertTrue(stage.file.delete())
            }
        }
    }

    @Test fun concurrentIdenticalStagesAcrossCanonicalRootAliasesPublishBothBindings() {
        val root = temporary.newFolder()
        val store = FileContentStore(root)
        val sibling = FileContentStore(File(root, "."))
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val committed = mutableListOf<ContentRef>() // Access only under canonical publication.
        try {
            val results =
                listOf(store, sibling).map { content ->
                    executor.submit<ContentRef> {
                        content.stageBinary().use { stage ->
                            stage.copyFrom(BYTES.inputStream(), 100) { }
                            ready.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            stage.publish({ }) { ref ->
                                committed.add(ref)
                                ref
                            }
                        }
                    }
                }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            assertEquals(0, StorageGarbageCollector.collectGarbage(root, { false }, 0).deletedTempFiles)
            assertEquals(2, root.walkTopDown().count { it.name.startsWith("binary.tmp-") })
            release.countDown()
            val refs = results.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(refs[0], refs[1])
            assertEquals(2, committed.size)
            assertTrue(BYTES.contentEquals(store.verifiedBinary(refs[0], 100) { }.readBytes()))
            assertFalse(root.walkTopDown().any { it.name.startsWith("binary.tmp-") })
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun gcCannotCrossBinaryPublicationAndReferenceCommit() {
        val root = temporary.newFolder()
        val store = FileContentStore(root)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val referenced = AtomicBoolean()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val publisher =
                executor.submit<ContentRef> {
                    store.stageBinary().use { stage ->
                        stage.copyFrom(BYTES.inputStream(), 100) { }
                        stage.publish({ }) { ref ->
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            referenced.set(true)
                            ref
                        }
                    }
                }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val collector =
                executor.submit<StorageGcResult> {
                    StorageGarbageCollector.collectGarbage(root, { referenced.get() }, 0)
                }
            assertFalse(collector.isDone)
            release.countDown()
            val ref = publisher.get(5, TimeUnit.SECONDS)
            assertEquals(0, collector.get(5, TimeUnit.SECONDS).deletedContentFiles)
            assertTrue(BYTES.contentEquals(store.verifiedBinary(ref, 100) { }.readBytes()))
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun realProcessDeathDropsLeaseAndNextProcessCanCollectAbandonedStage() {
        val root = temporary.newFolder()
        val marker = File(temporary.root, "child-ready")
        val output = File(temporary.root, "child-output")
        val classpath =
            listOf(javaClass, FileContentStore::class.java, kotlin.Unit::class.java)
                .map { File(checkNotNull(it.protectionDomain).codeSource.location.toURI()).path }
                .distinct()
                .joinToString(File.pathSeparator)
        val process =
            ProcessBuilder(
                File(System.getProperty("java.home"), "bin/java").path,
                "-cp",
                classpath,
                BinaryStageChild::class.java.name,
                root.path,
                marker.path,
            ).redirectErrorStream(true).redirectOutput(output).start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!marker.exists() && process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(output.readText(), marker.exists())
            assertTrue(process.isAlive)
            val stage = root.walkTopDown().single { it.name.startsWith("binary.tmp-") }
            assertTrue(BYTES.contentEquals(stage.readBytes()))
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
            val result = StorageGarbageCollector.collectGarbage(root, { false }, 0)
            assertEquals(1, result.deletedTempFiles)
            assertEquals(BYTES.size.toLong(), result.freedBytes)
            assertFalse(root.walkTopDown().any { it.isFile })
        } finally {
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        }
    }

    companion object {
        private val BYTES = "%PDF-private-stage".toByteArray()
    }
}

/** Separate OS process, killed with an active lease after a partial source copy. */
object BinaryStageChild {
    @JvmStatic fun main(arguments: Array<String>) {
        FileContentStore(File(arguments[0])).stageBinary().use { stage ->
            stage.copyFrom(
                object : ByteArrayInputStream("%PDF-private-stage".toByteArray()) {
                    override fun read(
                        bytes: ByteArray,
                        offset: Int,
                        length: Int,
                    ): Int {
                        if (pos == count) {
                            File(arguments[1]).writeText("stage copy is blocked")
                            CountDownLatch(1).await()
                        }
                        return super.read(bytes, offset, length)
                    }
                },
                100,
            ) { }
        }
    }
}
