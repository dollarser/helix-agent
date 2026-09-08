package com.helix.app.proot

import com.helix.core.storage.content.FileContentStore
import com.helix.runtime.proot.core.JobManifest
import com.helix.runtime.proot.core.JobManifestCodec
import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.runtime.proot.core.JobZipWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProotResultPreviewTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun displaysStreamsAndArtifactMetadataWithoutChangingArchive() {
        val archive = archive(mapOf("stdout.txt" to "output", "stderr.txt" to "error", "result.txt" to "artifact"))
        val originalHash = FileContentStore.sha256Hex(archive)
        val output = ProotResultPreview.read(archive, temporary.newFolder())
        assertEquals("output", output.stdout)
        assertEquals("error", output.stderr)
        assertFalse(output.truncated)
        assertEquals("result.txt", output.files.single().path)
        assertEquals(8L, output.files.single().size)
        assertEquals(FileContentStore.sha256Hex("artifact".toByteArray()), output.files.single().sha256)
        assertEquals(originalHash, FileContentStore.sha256Hex(archive))
    }

    @Test
    fun limitsStreamWithoutSplittingASurrogatePair() {
        val prefix = "x".repeat(ProotResultPreview.MAX_STREAM_CHARACTERS - 1)
        val output = ProotResultPreview.read(archive(mapOf("stdout.txt" to prefix + "😀tail")), temporary.newFolder())
        assertEquals(prefix, output.stdout)
        assertTrue(output.truncated)
        assertEquals("", output.stderr)
    }

    @Test
    fun exactLimitIsNotMarkedTruncated() {
        val text = "x".repeat(ProotResultPreview.MAX_STREAM_CHARACTERS)
        val output = ProotResultPreview.read(archive(mapOf("stderr.txt" to text)), temporary.newFolder())
        assertEquals(text, output.stderr)
        assertFalse(output.truncated)
    }

    @Test
    fun malformedArchiveIsRejectedAndTemporaryExtractionRemoved() {
        val archive = temporary.newFile().apply { writeText("not a ZIP") }
        val scratch = temporary.newFolder()
        assertThrows(Exception::class.java) { ProotResultPreview.read(archive, scratch) }
        assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }

    private fun archive(contents: Map<String, String>): File {
        val root = temporary.newFolder()
        val files = contents.toSortedMap().map { (name, text) -> File(root, name).apply { writeText(text) } }
        val entries = files.map { JobManifestEntry(it.name, FileContentStore.sha256Hex(it), it.length()) }
        val archive = temporary.newFile()
        JobZipWriter(archive.outputStream()).use { writer ->
            writer.writeManifest(JobManifestCodec.encode(JobManifest(entries)))
            files.forEach { writer.writeEntry(it.name, it) }
        }
        return archive
    }
}
