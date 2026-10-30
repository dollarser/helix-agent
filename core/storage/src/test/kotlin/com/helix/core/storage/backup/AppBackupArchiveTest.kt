package com.helix.core.storage.backup

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.SequenceInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppBackupArchiveTest {
    private val limits =
        AppBackupLimits(
            contentBytes = 8192,
            fileBytes = 4096,
            metadataBytes = 128,
            manifestBytes = 4096,
            entries = 6,
        )

    @Test fun fullPayloadAndMetadataBudgetsRoundTripIndependentlyOfManifest() =
        fixture { root ->
            val state = File(root, "state").apply { writeBytes(ByteArray(128)) }
            val first = File(root, "first").apply { writeBytes(ByteArray(4096) { 1 }) }
            val second = File(root, "second").apply { writeBytes(ByteArray(4096) { 2 }) }
            val files = mapOf("state.json" to state, blob(first) to first, blob(second) to second)
            val output = ByteArrayOutputStream()
            AppBackupArchive(limits).write(files, output) {}
            val imported = AppBackupArchive(limits).read(output.toByteArray().inputStream(), File(root, "import")) {}
            assertEquals(files.keys, imported.keys)
            files.forEach { (name, file) -> assertArrayEquals(file.readBytes(), imported.getValue(name).readBytes()) }
            state.appendBytes(byteArrayOf(1))
            assertThrows(IllegalArgumentException::class.java) {
                AppBackupArchive(limits).write(files, ByteArrayOutputStream()) {}
            }
        }

    @Test fun invalidPathsDuplicateNamesAndActualInflationAreRejected() =
        fixture { root ->
            val paths =
                listOf(
                    "../state.json",
                    "/state.json",
                    "blobs/../state.json",
                    "credentials.txt",
                    "blobs/a\\b",
                )
            paths.forEachIndexed { index, name ->
                val bytes = zip(name to byteArrayOf(1))
                val destination = File(root, "path$index")
                assertThrows(IllegalArgumentException::class.java) {
                    AppBackupArchive(limits).read(bytes.inputStream(), destination) {}
                }
            }
            val oversized = zip("state.json" to ByteArray(129))
            assertThrows(IllegalArgumentException::class.java) {
                AppBackupArchive(limits).read(oversized.inputStream(), File(root, "oversize")) {}
            }
            val repeated = zip("state.json" to byteArrayOf(1), "other.json" to byteArrayOf(2))
            val old = "other.json".toByteArray()
            val replacement = "state.json".toByteArray()
            for (offset in 0..repeated.size - old.size) {
                if (old.indices.all { repeated[offset + it] == old[it] }) replacement.copyInto(repeated, offset)
            }
            assertThrows(IllegalArgumentException::class.java) {
                AppBackupArchive(limits).read(repeated.inputStream(), File(root, "duplicate")) {}
            }
            val state = File(root, "state").apply { writeText("{}") }
            val output = ByteArrayOutputStream()
            AppBackupArchive(limits).write(mapOf("state.json" to state), output) {}
            val trailer = ByteArray(limits.archiveBytes.toInt()).inputStream()
            val stream = SequenceInputStream(output.toByteArray().inputStream(), trailer)
            assertThrows(IllegalArgumentException::class.java) {
                AppBackupArchive(limits).read(stream, File(root, "trailer")) {}
            }
        }

    @Test fun cancellationAndIncorrectBlobIdentityCannotProduceAcceptedArchive() =
        fixture { root ->
            val state = File(root, "state").apply { writeText("{}") }
            val payload = File(root, "blob").apply { writeBytes(ByteArray(4096)) }
            assertThrows(IllegalArgumentException::class.java) {
                val wrongIdentity = mapOf("state.json" to state, "blobs/${"a".repeat(64)}" to payload)
                AppBackupArchive(limits).write(wrongIdentity, ByteArrayOutputStream()) {}
            }
            val output = ByteArrayOutputStream()
            AppBackupArchive(limits).write(mapOf("state.json" to state, blob(payload) to payload), output) {}
            var checks = 0
            assertThrows(CancellationException::class.java) {
                AppBackupArchive(limits).read(output.toByteArray().inputStream(), File(root, "cancel")) {
                    if (++checks == 3) throw CancellationException("synthetic")
                }
            }
            assertEquals(3, checks)
        }

    private fun blob(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        return "blobs/$hash"
    }

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream()
            .also { output ->
                ZipOutputStream(output).use { zip ->
                    entries.forEach { (name, bytes) ->
                        zip.putNextEntry(ZipEntry(name))
                        zip.write(bytes)
                        zip.closeEntry()
                    }
                }
            }.toByteArray()

    private fun fixture(block: (File) -> Unit) {
        val root = Files.createTempDirectory("backup-archive-test").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
