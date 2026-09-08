package com.helix.app.proot

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.runtime.proot.core.JobZipWriter
import com.helix.runtime.proot.ipc.ProotJobArchive
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class ProotResultStoreDeviceTest {
    @Test
    fun persistsOnceRejectsCorruptionAndRegistersPrivacyCleanup() {
        withStore { storage, root, store, archive, record ->
            val file = archive.inputStream().use { store.persist("turn", "call", record, it) }
            archive.inputStream().use { store.persist("turn", "call", record, it) }
            assertEquals(1, storage.artifacts.listBySession("session").size)
            assertEquals(FileContentStore.sha256Hex(archive), FileContentStore.sha256Hex(file))
            val original = file.readBytes()
            file.writeBytes(original.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() })
            assertThrows(IllegalStateException::class.java) { store.readLocal("turn", "call") }
            file.writeBytes(original)
            assertEquals(file, store.readLocal("turn", "call"))
            val relative = file.relativeTo(root).invariantSeparatorsPath
            assertEquals(listOf(relative), storage.deleteSessionPermanently("session").unreferencedWorkspacePaths)
        }
    }

    @Test
    fun rejectsWrongBindingAndManifestWithoutReplacingSavedResult() {
        withStore { storage, root, store, archive, record ->
            val file = archive.inputStream().use { store.persist("turn", "call", record, it) }
            val hash = FileContentStore.sha256Hex(file)
            val wrongRecords =
                listOf(
                    record.copy(executionId = "other"),
                    record.copy(outputManifestSha256 = "b".repeat(64)),
                )
            for (wrong in wrongRecords) {
                assertThrows(IllegalStateException::class.java) {
                    archive.inputStream().use { store.persist("turn", "call", wrong, it) }
                }
            }
            assertEquals(hash, FileContentStore.sha256Hex(file))
            assertEquals(1, storage.artifacts.listBySession("session").size)
            assertTrue(File(root, "scratch").listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun recoveryPersistsBeforeAckAndLocalReadDoesNotContactRuntime() {
        withStore { storage, _, store, archive, record ->
            interrupt(storage)
            var acknowledgements = 0
            val recovery =
                ProotResultRecovery(
                    storage,
                    store,
                    { record },
                    {
                        ProotJobArchive(
                            record,
                            ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY),
                        )
                    },
                    {
                        assertEquals(
                            FileContentStore.sha256Hex(archive),
                            FileContentStore.sha256Hex(
                                requireNotNull(store.readLocal("turn", "call")),
                            ),
                        )
                        acknowledgements++
                        record.copy(reconciledAtEpochMs = 4)
                    },
                )
            assertTrue(requireNotNull(recovery.recover("turn", "call", false)).acknowledged)
            assertEquals(1, acknowledgements)
            val offline =
                ProotResultRecovery(
                    storage,
                    store,
                    { error("Unexpected query") },
                    { error("Unexpected fetch") },
                    { error("Unexpected ACK") },
                )
            assertTrue(requireNotNull(offline.recover("turn", "call", true)).file.isFile)
        }
    }

    @Test
    fun lostRecoveryAckKeepsResultAndExplicitRetryUsesOnlyTheOriginalJob() {
        withStore { storage, _, store, archive, record ->
            interrupt(storage)
            var fetches = 0
            var acknowledgements = 0
            val recovery =
                ProotResultRecovery(
                    storage,
                    store,
                    { job ->
                        assertEquals(record.jobId, job)
                        if (acknowledgements == 0) record else record.copy(reconciledAtEpochMs = 4)
                    },
                    {
                        fetches++
                        ProotJobArchive(record, ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY))
                    },
                    { original ->
                        assertEquals(record.terminalCommit, original.terminalCommit)
                        if (acknowledgements > 0) assertEquals(4L, original.reconciledAtEpochMs)
                        acknowledgements++
                        if (acknowledgements == 1) throw android.os.RemoteException("ACK response lost")
                        record.copy(reconciledAtEpochMs = 4)
                    },
                )
            val first = requireNotNull(recovery.recover("turn", "call", false))
            assertEquals(false, first.acknowledged)
            val hash = FileContentStore.sha256Hex(first.file)
            assertTrue(requireNotNull(recovery.recover("turn", "call", true)).file.isFile)
            assertEquals(1, acknowledgements)
            val retry = requireNotNull(recovery.recover("turn", "call", false))
            assertTrue(retry.acknowledged)
            assertEquals(hash, FileContentStore.sha256Hex(retry.file))
            assertEquals(1, fetches)
            assertEquals(2, acknowledgements)
            assertEquals(1, storage.artifacts.listBySession("session").size)
            assertEquals("INTERRUPTED", storage.turns.resolve("turn").state)
        }
    }

    @Test
    fun recoveryNeverAcknowledgesRejectedOutput() {
        withStore { storage, _, store, archive, record ->
            interrupt(storage)
            val wrong = record.copy(outputManifestSha256 = "b".repeat(64))
            var acknowledgements = 0
            val recovery =
                ProotResultRecovery(
                    storage,
                    store,
                    { wrong },
                    {
                        ProotJobArchive(
                            wrong,
                            ParcelFileDescriptor.open(archive, ParcelFileDescriptor.MODE_READ_ONLY),
                        )
                    },
                    {
                        acknowledgements++
                        record.copy(reconciledAtEpochMs = 4)
                    },
                )
            assertThrows(IllegalStateException::class.java) { recovery.recover("turn", "call", false) }
            assertEquals(0, acknowledgements)
            assertTrue(storage.artifacts.listBySession("session").isEmpty())
        }
    }

    @Test
    fun unavailableAcknowledgementKeepsVerifiedLocalResult() {
        withStore { storage, _, store, archive, record ->
            val committer = ProotResultCommitter(storage, store) { throw android.os.RemoteException("lost") }
            assertEquals(false, committer.commit("turn", "call", record, archive))
            assertTrue(requireNotNull(store.readLocal("turn", "call")).isFile)
            val event = storage.auditEvents.listByCorrelation("session").single { it.type == "proot.result_ack" }
            assertTrue(event.redactedPayload.contains("\"acknowledged\":false"))
        }
    }

    @Test
    fun persistenceFailureNeverAcknowledges() {
        withStore { storage, root, _, archive, record ->
            val blocked = File(root, "blocked").apply { writeText("not a directory") }
            val store = ProotResultStore(storage, blocked, File(root, "scratch"))
            var acknowledgements = 0
            val committer =
                ProotResultCommitter(storage, store) {
                    acknowledgements++
                    null
                }
            assertThrows(IllegalStateException::class.java) { committer.commit("turn", "call", record, archive) }
            assertEquals(0, acknowledgements)
            assertTrue(storage.artifacts.listBySession("session").isEmpty())
        }
    }

    private fun interrupt(storage: HelixStorage) {
        storage.turns.updateState(
            storage.turns.resolve("turn"),
            com.helix.core.model.TurnState.INTERRUPTED,
            0,
            3,
            "PROCESS_DEATH",
        )
    }

    private fun withStore(block: (HelixStorage, File, ProotResultStore, File, ProotJobRecord) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "proot-result-${UUID.randomUUID()}"
        val root = File(context.cacheDir, name)
        val storage = HelixStorage.open(context, name, File(root, "content"))
        try {
            storage.sessions.create("session", "Result fixture", null, null, 1)
            storage.turns.start("turn", "session", 2)
            storage.toolCalls.append("stored-call", "turn", "call", "bash", "1", "{}", "INTERRUPTED")
            storage.auditEvents.append(
                "proot-job-call",
                "session",
                "proot.job_prepared",
                "platform",
                """{"version":1,"toolCallId":"call","turnId":"turn","jobId":"job_0123456789ab",
                "executionId":"execution","inputManifestSha256":"${"a".repeat(64)}"}""",
                3,
            )
            val (archive, manifestHash) = archive(root)
            val record =
                ProotJobRecord(
                    "job_0123456789ab",
                    "execution",
                    "a".repeat(64),
                    ProotJobState.SUCCEEDED,
                    1,
                    terminalAtEpochMs = 2,
                    exitCode = 0,
                    outputManifestSha256 = manifestHash,
                )
            block(storage, root, ProotResultStore(storage, root, File(root, "scratch")), archive, record)
        } finally {
            storage.close()
            context.deleteDatabase(name)
            root.deleteRecursively()
        }
    }

    private fun archive(root: File): Pair<File, String> {
        check(root.mkdirs() || root.isDirectory)
        val stdout = File(root, "stdout.txt").apply { writeText("verified result") }
        val entry = JobManifestEntry("stdout.txt", FileContentStore.sha256Hex(stdout), stdout.length())
        val manifest = JobManifestCodec.encode(JobManifest(listOf(entry)))
        val archive = File(root, "fixture.zip")
        JobZipWriter(archive.outputStream()).use {
            it.writeManifest(manifest)
            it.writeEntry("stdout.txt", stdout)
        }
        return archive to FileContentStore.sha256Hex(manifest.toByteArray())
    }
}
