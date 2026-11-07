package com.helix.core.storage

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.content.StorageGarbageCollector
import com.helix.core.storage.entity.DocumentSnapshotEntity
import com.helix.core.storage.entity.SessionEntity
import com.helix.core.storage.repository.DocumentSnapshotRepository
import com.helix.core.storage.repository.MessageRepository
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Disk Room + real resolver streams; provider and all documents are private synthetic fixtures. */
@SdkSuppress(minSdkVersion = 29)
class DocumentSnapshotPublicationDeviceTest {
    @Test fun slowResolverOpenDoesNotBlockAnotherSessionMessageCommit() =
        fixture { f ->
            f.provider.pauseOpen = true
            val snapshot = f.startSnapshot()
            await(f.provider.openEntered)
            try {
                f.assertIndependentMessageCommitted()
                assertFalse(snapshot.isDone)
                assertTrue(
                    f.database
                        .documentSnapshotDao()
                        .listBySession(SELECTED)
                        .isEmpty(),
                )
            } finally {
                f.provider.releaseOpen.countDown()
            }
            f.assertSnapshot(snapshot.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }

    @Test fun slowResolverReadDoesNotBlockAnotherSessionMessageCommit() =
        fixture { f ->
            f.provider.pauseRead = true
            val snapshot = f.startSnapshot()
            await(f.readProgress)
            try {
                f.assertIndependentMessageCommitted()
                assertFalse(snapshot.isDone)
                assertTrue(
                    f.database
                        .documentSnapshotDao()
                        .listBySession(SELECTED)
                        .isEmpty(),
                )
            } finally {
                f.provider.releaseRead.countDown()
            }
            f.assertSnapshot(snapshot.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }

    @Test fun cancellationDuringPipeReadLeavesNoSnapshotOrDamageToExistingReference() =
        fixture { f ->
            val retained = f.snapshots.create(OTHER, SOURCE_PATH, f::open) {}
            f.provider.pauseRead = true
            val snapshot = f.startSnapshot()
            await(f.readProgress)
            f.provider.readerMayCancel = true
            f.cancelled.set(true)
            f.provider.releaseRead.countDown()
            val failure =
                assertThrows(ExecutionException::class.java) {
                    snapshot.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            assertTrue(failure.cause is CancellationException)
            f.assertNoSelectedSnapshotAndRetainedContent(retained)
        }

    @Test fun reliablePipeIoFailureLeavesNoSnapshotOrDamageToExistingReference() =
        fixture { f ->
            val retained = f.snapshots.create(OTHER, SOURCE_PATH, f::open) {}
            f.provider.failRead = true
            assertThrows(IOException::class.java) {
                f.snapshots.create(SELECTED, SOURCE_PATH, f::open) {}
            }
            f.assertNoSelectedSnapshotAndRetainedContent(retained)
        }

    @Test fun resolverOpenFailureDoesNotPublishSnapshot() =
        fixture { f ->
            f.provider.failOpen = true
            assertThrows(FileNotFoundException::class.java) {
                f.snapshots.create(SELECTED, SOURCE_PATH, f::open) {}
            }
            assertTrue(
                f.database
                    .documentSnapshotDao()
                    .listBySession(SELECTED)
                    .isEmpty(),
            )
            assertTrue(f.contentFiles().isEmpty())
        }

    @Test fun revokedSourceAccessCannotBeReplacedByCachedSnapshotOrAnotherSession() =
        fixture { f ->
            val snapshot = f.snapshots.create(SELECTED, SOURCE_PATH, f::open) {}
            f.provider.revoked = true
            assertThrows(SecurityException::class.java) {
                f.snapshots.resolve(SELECTED, snapshot.id, f::open) {}
            }
            val expired =
                assertThrows(DocumentSnapshotRepository.Unavailable::class.java) {
                    f.snapshots.resolve(OTHER, snapshot.id, f::open) {}
                }
            assertEquals("REF_EXPIRED", expired.code)
            // Revoking the test source denies resolve; it does not delete already-owned bytes.
            f.assertSnapshot(snapshot)
            f.provider.revoked = false
            assertEquals(snapshot, f.snapshots.resolve(SELECTED, snapshot.id, f::open) {})
        }

    @Test fun foreignKeyFailureDoesNotDeleteAnExistingSameHashSnapshot() =
        fixture { f ->
            val retained = f.snapshots.create(OTHER, SOURCE_PATH, f::open) {}
            assertThrows(SQLiteConstraintException::class.java) {
                f.snapshots.create("missing-session", SOURCE_PATH, f::open) {}
            }
            assertTrue(
                f.database
                    .documentSnapshotDao()
                    .listBySession("missing-session")
                    .isEmpty(),
            )
            f.assertNoSelectedSnapshotAndRetainedContent(retained)
            val reused = f.snapshots.create(SELECTED, SOURCE_PATH, f::open) {}
            assertEquals(retained.contentRef, reused.contentRef)
            assertEquals(2, f.database.documentSnapshotDao().countByContentRef(retained.contentRef))
            assertEquals(1, f.contentFiles().size)
        }

    @Test fun sessionDeletedDuringStagingCannotAcquireSnapshotAndGcPreservesOtherSessionContent() =
        fixture { f ->
            f.provider.pauseRead = true
            val snapshot = f.startSnapshot()
            await(f.readProgress)
            try {
                f.assertIndependentMessageCommitted()
                val deleted =
                    f.executor
                        .submit<Int> {
                            f.database.sessionDao().deletePermanently(SELECTED)
                        }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                assertEquals(1, deleted)
                assertNull(f.database.sessionDao().byId(SELECTED))
                assertFalse(snapshot.isDone)
            } finally {
                f.provider.releaseRead.countDown()
            }
            val failure =
                assertThrows(ExecutionException::class.java) {
                    snapshot.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }
            assertTrue(failure.cause is SQLiteConstraintException)
            assertTrue(
                f.database
                    .documentSnapshotDao()
                    .listBySession(SELECTED)
                    .isEmpty(),
            )
            val message = requireNotNull(f.database.messageDao().byId("independent"))
            val retained = ContentRef.parse(requireNotNull(message.contentRef))
            StorageGarbageCollector.collectGarbage(f.root, f.database, 0, Long.MAX_VALUE)
            assertEquals(message, f.database.messageDao().byId("independent"))
            assertEquals("unrelated durable message", f.store.read(retained))
            // The failed snapshot's new canonical bytes and staging are gone, not the other reference.
            assertEquals(listOf(File(f.root, retained.relativePath)), f.contentFiles())
        }

    @Test fun garbageCollectionDuringPausedReadProtectsStagingAndPublishedReferences() =
        fixture { f ->
            f.provider.pauseRead = true
            val snapshot = f.startSnapshot()
            await(f.readProgress)
            try {
                // Zero grace and a future clock make active staging eligible absent a lease.
                f.executor
                    .submit {
                        StorageGarbageCollector.collectGarbage(f.root, f.database, 0, Long.MAX_VALUE)
                    }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                f.assertIndependentMessageCommitted()
                assertFalse(snapshot.isDone)
            } finally {
                f.provider.releaseRead.countDown()
            }
            val published = snapshot.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            f.assertSnapshot(published)
            StorageGarbageCollector.collectGarbage(f.root, f.database, 0, Long.MAX_VALUE)
            f.assertSnapshot(published)
            assertTrue(f.root.walkTopDown().none { it.isFile && it.name.contains(".tmp") })
        }

    private class Fixture(
        val database: HelixDatabase,
        val root: File,
        val resolver: ContentResolver,
        val provider: PipeDocumentProvider,
        val executor: ExecutorService,
    ) {
        val store = FileContentStore(root)
        val snapshots =
            DocumentSnapshotRepository(database.documentSnapshotDao(), store) { action ->
                database.runInTransaction { action() }
            }
        private val messages =
            MessageRepository(
                database.messageDao(),
                store,
                database.sessionSearchDao(),
                { action -> database.runInTransaction { action() } },
            )
        val cancelled = AtomicBoolean(false)
        val readProgress = CountDownLatch(1)
        private val bytesRead = AtomicBoolean(false)

        fun open(path: String): InputStream {
            check(path == SOURCE_PATH)
            val stream = resolver.openInputStream(SOURCE_URI) ?: throw FileNotFoundException(path)
            return object : FilterInputStream(stream) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = super.read(buffer, offset, length).also { if (it > 0) bytesRead.set(true) }
            }
        }

        fun startSnapshot(): Future<DocumentSnapshotEntity> {
            bytesRead.set(false)
            return executor.submit<DocumentSnapshotEntity> {
                snapshots.create(SELECTED, SOURCE_PATH, ::open) {
                    if (bytesRead.get()) readProgress.countDown()
                    if (cancelled.get()) throw CancellationException("synthetic cancellation")
                }
            }
        }

        fun assertIndependentMessageCommitted() {
            val message =
                executor
                    .submit<com.helix.core.storage.entity.MessageEntity> {
                        messages.append("independent", OTHER, null, "USER", "TEXT", "unrelated durable message")
                    }.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // The repository has returned from its real Room transaction while the source is paused.
            assertEquals(message, database.messageDao().byId(message.id))
            assertEquals("unrelated durable message", store.read(ContentRef.parse(requireNotNull(message.contentRef))))
        }

        fun assertSnapshot(snapshot: DocumentSnapshotEntity) {
            assertEquals(snapshot, database.documentSnapshotDao().find(snapshot.sessionId, snapshot.id))
            assertArrayEquals(PDF_BYTES, snapshots.file(snapshot.sessionId, snapshot.id) {}.readBytes())
        }

        fun assertNoSelectedSnapshotAndRetainedContent(retained: DocumentSnapshotEntity) {
            assertTrue(database.documentSnapshotDao().listBySession(SELECTED).isEmpty())
            assertSnapshot(retained)
            StorageGarbageCollector.collectGarbage(root, database, 0, Long.MAX_VALUE)
            assertSnapshot(retained)
            assertEquals(1, contentFiles().size)
        }

        fun contentFiles(): List<File> = root.walkTopDown().filter { it.isFile }.toList()
    }

    /** No manifest registration, persistable grants, external providers, or user documents. */
    private class PipeDocumentProvider : ContentProvider() {
        @Volatile var pauseOpen = false

        @Volatile var pauseRead = false

        @Volatile var failOpen = false

        @Volatile var failRead = false

        @Volatile var revoked = false

        @Volatile var readerMayCancel = false
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        private val writers = Executors.newCachedThreadPool()
        private val pending = mutableListOf<Future<*>>()

        override fun onCreate(): Boolean = true

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor {
            check(uri == SOURCE_URI && mode == "r")
            if (revoked) throw SecurityException("synthetic source grant revoked")
            if (failOpen) throw FileNotFoundException("synthetic provider open failure")
            if (pauseOpen) {
                openEntered.countDown()
                await(releaseOpen)
            }
            val pipe = ParcelFileDescriptor.createReliablePipe()
            synchronized(pending) {
                pending +=
                    writers.submit {
                        try {
                            ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { output ->
                                output.write(PDF_BYTES, 0, 5)
                                if (pauseRead) await(releaseRead)
                                if (failRead) {
                                    pipe[1].closeWithError("synthetic provider read failure")
                                } else {
                                    output.write(PDF_BYTES, 5, PDF_BYTES.size - 5)
                                }
                            }
                        } catch (failure: IOException) {
                            // Cancellation legitimately closes the read end before the producer finishes.
                            if (!readerMayCancel) throw failure
                        }
                    }
            }
            return pipe[0]
        }

        val isTerminated: Boolean get() = writers.isTerminated

        fun finish() {
            releaseOpen.countDown()
            releaseRead.countDown()
            writers.shutdown()
            check(writers.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Provider writer did not terminate" }
            var failure: Throwable? = null
            synchronized(pending) {
                pending.forEach { future ->
                    val current = runCatching { future.get() }.exceptionOrNull()
                    if (current != null) {
                        val previous = failure
                        if (previous == null) failure = current else previous.addSuppressed(current)
                    }
                }
            }
            failure?.let { throw AssertionError("Provider writer failed", it) }
        }

        override fun getType(uri: Uri): String = "application/pdf"

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = throw UnsupportedOperationException()

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = throw UnsupportedOperationException()

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = throw UnsupportedOperationException()

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = throw UnsupportedOperationException()
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "snapshot-publication-${UUID.randomUUID()}")
        check(directory.mkdirs())
        val databaseFile = File(directory, "fixture.db")
        val database = Room.databaseBuilder(context, HelixDatabase::class.java, databaseFile.absolutePath).build()
        val provider = PipeDocumentProvider()
        provider.attachInfo(
            context,
            ProviderInfo().apply {
                authority = AUTHORITY
                exported = false
            },
        )
        val resolver = ContentResolver.wrap(provider)
        val executor = Executors.newFixedThreadPool(3)
        val primary =
            runCatching {
                database.sessionDao().insert(SessionEntity(SELECTED, "selected", null, null, 1, null))
                database.sessionDao().insert(SessionEntity(OTHER, "other", null, null, 1, null))
                block(Fixture(database, File(directory, "bodies"), resolver, provider, executor))
            }.exceptionOrNull()
        val cleanupFailures = finishFixture(database, directory, provider, executor)
        val failure = primary ?: cleanupFailures.firstOrNull()
        cleanupFailures.filter { it !== failure }.forEach { failure?.addSuppressed(it) }
        failure?.let { throw it }
    }

    private fun finishFixture(
        database: HelixDatabase,
        directory: File,
        provider: PipeDocumentProvider,
        executor: ExecutorService,
    ): List<Throwable> {
        provider.releaseOpen.countDown()
        provider.releaseRead.countDown()
        executor.shutdown()
        val executorFailure =
            runCatching {
                check(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    "Snapshot workers did not terminate; retaining private fixture"
                }
            }.exceptionOrNull()
        val providerFailure = runCatching { provider.finish() }.exceptionOrNull()
        // Never remove a database/body directory while a timed-out worker may still use it.
        val filesFailure =
            if (executor.isTerminated && provider.isTerminated) {
                runCatching {
                    database.close()
                    check(directory.deleteRecursively()) { "Cannot remove private fixture" }
                }.exceptionOrNull()
            } else {
                null
            }
        return listOfNotNull(executorFailure, providerFailure, filesFailure)
    }

    companion object {
        private const val SELECTED = "snapshot-session"
        private const val OTHER = "message-session"
        private const val AUTHORITY = "com.helix.test.private.snapshot"
        private const val SOURCE_PATH = "scope:test/document.pdf"
        private const val TIMEOUT_SECONDS = 10L
        private val SOURCE_URI = Uri.parse("content://$AUTHORITY/document.pdf")
        private val PDF_BYTES = "%PDF-1.7\nsynthetic private snapshot\n%%EOF\n".toByteArray(Charsets.US_ASCII)

        private fun await(latch: CountDownLatch) {
            check(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Synthetic source gate timed out" }
        }
    }
}
