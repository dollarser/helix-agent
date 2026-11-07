package com.helix.core.storage.repository

import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.content.StorageGarbageCollector
import com.helix.core.storage.dao.DocumentSnapshotDao
import com.helix.core.storage.entity.DocumentSnapshotEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DocumentSnapshotPublicationTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Index : DocumentSnapshotDao {
        val rows = ConcurrentHashMap<String, DocumentSnapshotEntity>()

        override fun insert(entity: DocumentSnapshotEntity) {
            check(rows.putIfAbsent(entity.id, entity) == null)
        }

        override fun find(
            sessionId: String,
            id: String,
        ) = rows[id]?.takeIf { it.sessionId == sessionId }

        override fun listBySession(sessionId: String) = rows.values.filter { it.sessionId == sessionId }

        override fun contentRefsBySession(sessionId: String) = listBySession(sessionId).map { it.contentRef }

        override fun countByContentRef(contentRef: String) = rows.values.count { it.contentRef == contentRef }
    }

    private class Gate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        fun hold() {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS))
        }

        fun await() {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
        }
    }

    private class SlowSource(
        private val phase: String,
    ) {
        val gate = Gate()
        val opens = AtomicInteger()
        val closed = AtomicBoolean()
        private val eof = AtomicBoolean()
        private val afterEof = AtomicInteger()

        fun open(): ByteArrayInputStream {
            assertEquals(1, opens.incrementAndGet())
            if (phase == "open") gate.hold()
            return object : ByteArrayInputStream(BYTES) {
                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (phase == "read" && pos == 0) gate.hold()
                    return super.read(bytes, offset, length).also { if (it < 0) eof.set(true) }
                }

                override fun close() {
                    if (phase == "close") gate.hold()
                    closed.set(true)
                    super.close()
                }
            }
        }

        fun checkActive() {
            // The first check after EOF precedes digest finalization; the next starts
            // the independent on-disk verification pass.
            val verifying = phase == "verification" && eof.get() && afterEof.incrementAndGet() == 2
            if (verifying) gate.hold()
        }
    }

    @Test fun slowOpenReadCloseAndStageVerificationDoNotBlockOtherSessionsOrGc() {
        for (phase in listOf("open", "read", "close", "verification")) {
            val root = temporary.newFolder(phase)
            val store = FileContentStore(root)
            val index = Index()
            val repository = DocumentSnapshotRepository(index, store) { it() }
            val source = SlowSource(phase)
            val gate = source.gate
            val executor = Executors.newFixedThreadPool(2)
            try {
                val snapshot =
                    executor.submit<DocumentSnapshotEntity> {
                        repository.create("slow", "scope:ws:slow.pdf", { source.open() }, source::checkActive)
                    }
                gate.await()
                assertFalse(snapshot.isDone)
                val other =
                    executor
                        .submit<ContentRef> {
                            val sibling = FileContentStore(File(root, "."))
                            sibling.withPublication {
                                val ref = sibling.write("other-session")
                                index.insert(
                                    DocumentSnapshotEntity(
                                        "other",
                                        "other",
                                        "scope:ws:other.pdf",
                                        "pdf",
                                        ref.toStorageString(),
                                    ),
                                )
                                ref
                            }
                        }.get(5, TimeUnit.SECONDS)
                val collection =
                    executor
                        .submit<com.helix.core.storage.content.StorageGcResult> {
                            StorageGarbageCollector.collectGarbage(
                                File(root, "."),
                                { index.countByContentRef(it) > 0 },
                                0,
                            )
                        }.get(5, TimeUnit.SECONDS)
                assertEquals(0, collection.deletedTempFiles)
                assertEquals("other-session", store.read(other))
                assertTrue(root.walkTopDown().any { it.name.startsWith("binary.tmp-") })
                gate.release.countDown()
                val result = snapshot.get(5, TimeUnit.SECONDS)
                assertEquals(String(BYTES), repository.file("slow", result.id) { }.readText())
                assertEquals(1, source.opens.get())
                assertTrue(source.closed.get())
                assertFalse(root.walkTopDown().any { it.name.startsWith("binary.tmp-") })
            } finally {
                gate.release.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun openReadCloseAndFormatFailuresLeaveNoBindingOrStage() {
        for (phase in listOf("open", "read", "close", "format")) {
            val root = temporary.newFolder(phase)
            val store = FileContentStore(root)
            val index = Index()
            val failure = IOException(phase)
            val repository = DocumentSnapshotRepository(index, store) { it() }
            val thrown =
                assertThrows(IOException::class.java) {
                    repository.create("s", "scope:ws:source.pdf", {
                        if (phase == "open") throw failure
                        object : ByteArrayInputStream(if (phase == "format") byteArrayOf(1, 2) else BYTES) {
                            override fun read(
                                bytes: ByteArray,
                                offset: Int,
                                length: Int,
                            ): Int {
                                if (phase == "read") throw failure
                                return super.read(bytes, offset, length)
                            }

                            override fun close() {
                                if (phase == "close") throw failure
                            }
                        }
                    }) { }
                }
            if (phase != "format") assertSame(failure, thrown)
            assertTrue(index.rows.isEmpty())
            assertFalse(root.walkTopDown().any { it.isFile })
        }
    }

    @Test fun cancellationWhileWaitingForPublicationCannotCommit() {
        val root = temporary.newFolder()
        val store = FileContentStore(root)
        val index = Index()
        val gate = Gate()
        val cancelled = AtomicBoolean()
        val worker = AtomicReference<Thread>()
        val executor = Executors.newSingleThreadExecutor()
        val repository = DocumentSnapshotRepository(index, store) { it() }
        try {
            val pending =
                executor.submit {
                    worker.set(Thread.currentThread())
                    assertThrows(InterruptedException::class.java) {
                        repository.create("s", "scope:ws:source.pdf", {
                            object : ByteArrayInputStream(BYTES) {
                                override fun close() = gate.hold()
                            }
                        }) { if (cancelled.get()) throw InterruptedException("cancelled") }
                    }
                }
            gate.await()
            store.withPublication {
                gate.release.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (worker.get().state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.yield()
                }
                assertEquals(Thread.State.BLOCKED, worker.get().state)
                cancelled.set(true)
            }
            pending.get(5, TimeUnit.SECONDS)
            assertTrue(index.rows.isEmpty())
            assertFalse(root.walkTopDown().any { it.isFile })
        } finally {
            gate.release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun cancellationWhileAllocatingStageDoesNotOpenTheSource() {
        val store = FileContentStore(temporary.newFolder())
        val index = Index()
        val repository = DocumentSnapshotRepository(index, store) { it() }
        val executor = Executors.newSingleThreadExecutor()
        val checked = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        val cancelled = AtomicBoolean()
        val opens = AtomicInteger()
        try {
            val pending =
                store.withPublication {
                    val future =
                        executor.submit {
                            worker.set(Thread.currentThread())
                            assertThrows(InterruptedException::class.java) {
                                repository.create("s", "scope:ws:source.pdf", {
                                    opens.incrementAndGet()
                                    BYTES.inputStream()
                                }) {
                                    checked.countDown()
                                    if (cancelled.get()) throw InterruptedException("cancelled")
                                }
                            }
                        }
                    assertTrue(checked.await(5, TimeUnit.SECONDS))
                    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                    while (worker.get().state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                        Thread.yield()
                    }
                    assertEquals(Thread.State.BLOCKED, worker.get().state)
                    cancelled.set(true)
                    future
                }
            pending.get(5, TimeUnit.SECONDS)
            assertEquals(0, opens.get())
            assertTrue(index.rows.isEmpty())
            assertFalse(store.root.walkTopDown().any { it.isFile })
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun transactionFailureLeavesOnlyCollectableOrphanAndNeverDeletesOtherReferences() {
        val root = temporary.newFolder()
        val store = FileContentStore(root)
        val index = Index()
        val existing =
            DocumentSnapshotRepository(index, store) { it() }
                .create("other", "scope:ws:other.pdf", { BYTES.inputStream() }) { }
        val failure = IllegalStateException("transaction rollback")
        val repository = DocumentSnapshotRepository(index, store) { throw failure }
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                repository.create("s", "scope:ws:new.pdf", { "%PDF-new".byteInputStream() }) { }
            },
        )
        assertSame(
            failure,
            assertThrows(IllegalStateException::class.java) {
                repository.create("s", "scope:ws:same.pdf", { BYTES.inputStream() }) { }
            },
        )
        assertEquals(1, index.rows.size)
        assertEquals(
            1,
            StorageGarbageCollector.collectGarbage(root, { index.countByContentRef(it) > 0 }, 0).deletedContentFiles,
        )
        assertEquals(String(BYTES), store.verifiedBinary(ContentRef.parse(existing.contentRef), 100) { }.readText())
        assertFalse(root.walkTopDown().any { it.name.startsWith("binary.tmp-") })
    }

    @Test fun repositoryTransactionRetainsPublicationUntilReferenceCommit() {
        val root = temporary.newFolder()
        val store = FileContentStore(root)
        val index = Index()
        val gate = Gate()
        val executor = Executors.newFixedThreadPool(2)
        val collectorThread = AtomicReference<Thread>()
        val collectorStarted = CountDownLatch(1)
        val repository =
            DocumentSnapshotRepository(index, store) { action ->
                action()
                gate.hold()
            }
        try {
            val snapshot =
                executor.submit<DocumentSnapshotEntity> {
                    repository.create("s", "scope:ws:source.pdf", { BYTES.inputStream() }) { }
                }
            gate.await()
            val collector =
                executor.submit<com.helix.core.storage.content.StorageGcResult> {
                    collectorThread.set(Thread.currentThread())
                    collectorStarted.countDown()
                    StorageGarbageCollector.collectGarbage(File(root, "."), { index.countByContentRef(it) > 0 }, 0)
                }
            assertTrue(collectorStarted.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (collectorThread.get().state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, collectorThread.get().state)
            assertFalse(collector.isDone)
            gate.release.countDown()
            val published = snapshot.get(5, TimeUnit.SECONDS)
            assertEquals(0, collector.get(5, TimeUnit.SECONDS).deletedContentFiles)
            assertEquals(String(BYTES), repository.file("s", published.id) { }.readText())
        } finally {
            gate.release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun corruptExistingHashFailsClosedWithoutRepairOrNewBinding() {
        val store = FileContentStore(temporary.newFolder())
        val index = Index()
        val repository = DocumentSnapshotRepository(index, store) { it() }
        val old = repository.create("other", "scope:ws:old.pdf", { BYTES.inputStream() }) { }
        val file = File(store.root, ContentRef.parse(old.contentRef).relativePath)
        val corrupt = BYTES.copyOf().also { it[it.lastIndex] = 0 }
        file.writeBytes(corrupt)
        assertThrows(IllegalArgumentException::class.java) {
            repository.create("s", "scope:ws:same.pdf", { BYTES.inputStream() }) { }
        }
        assertEquals(1, index.rows.size)
        assertTrue(corrupt.contentEquals(file.readBytes()))
        assertFalse(store.root.walkTopDown().any { it.name.startsWith("binary.tmp-") })
    }

    companion object {
        private val BYTES = "%PDF-private-fixture".toByteArray()
    }
}
