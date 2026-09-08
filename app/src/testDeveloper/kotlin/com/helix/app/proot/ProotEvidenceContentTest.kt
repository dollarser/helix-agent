package com.helix.app.proot

import com.helix.core.storage.content.FileContentStore
import com.helix.runtime.proot.core.JobArchiveLimits
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.runtime.proot.core.JobZipWriter
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.InterruptedIOException

class ProotEvidenceContentTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun readsEntireArtifactBeyondPreviewLimitWithoutChangingArchive() {
        val bytes = ("正文😀".repeat(20000)).toByteArray()
        val archive = archive(bytes)
        val hash = FileContentStore.sha256Hex(archive)
        val scratch = temporary.newFolder()
        assertArrayEquals(bytes, ProotEvidenceContent.read(archive, scratch, "result.txt"))
        assertEquals(hash, FileContentStore.sha256Hex(archive))
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun exactLimitAndEmptyFileAreComplete() {
        for (bytes in listOf(byteArrayOf(), ByteArray(ProotEvidenceContent.MAX_BYTES) { 42 })) {
            assertArrayEquals(bytes, ProotEvidenceContent.read(archive(bytes), temporary.newFolder(), "result.txt"))
        }
    }

    @Test
    fun oversizedOrMissingEntryNeverReturnsAPrefix() {
        val archive = archive(ByteArray(ProotEvidenceContent.MAX_BYTES + 1))
        val scratch = temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            ProotEvidenceContent.read(archive, scratch, "result.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProotEvidenceContent.read(archive, scratch, "../result.txt")
        }
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun rejectsMismatchedManifestHashAndCleansExtraction() {
        val archive = temporary.newFile()
        val manifest = JobManifest(listOf(JobManifestEntry("result.txt", "0".repeat(64), 6)))
        java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(JobArchiveLimits.MANIFEST_ENTRY))
            zip.write(JobManifestCodec.encode(manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("result.txt"))
            zip.write("actual".toByteArray())
            zip.closeEntry()
        }
        val scratch = temporary.newFolder()
        assertThrows(Exception::class.java) { ProotEvidenceContent.read(archive, scratch, "result.txt") }
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun interruptionDuringReadCleansExtractionAndLeavesArchiveReusable() {
        val bytes = ByteArray(32768) { 7 }
        val archive = archive(bytes)
        val scratch = temporary.newFolder()
        var checkpoints = 0
        assertThrows(InterruptedIOException::class.java) {
            ProotEvidenceContent.read(archive, scratch, "result.txt") { ++checkpoints == 4 }
        }
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
        assertArrayEquals(bytes, ProotEvidenceContent.read(archive, scratch, "result.txt"))
    }

    private fun archive(bytes: ByteArray): File {
        val file = temporary.newFile().apply { writeBytes(bytes) }
        val archive = temporary.newFile()
        JobZipWriter(archive.outputStream()).use { writer ->
            writer.writeManifest(
                JobManifestCodec.encode(
                    JobManifest(
                        listOf(JobManifestEntry("result.txt", FileContentStore.sha256Hex(bytes), file.length())),
                    ),
                ),
            )
            writer.writeEntry("result.txt", file)
        }
        return archive
    }
}
