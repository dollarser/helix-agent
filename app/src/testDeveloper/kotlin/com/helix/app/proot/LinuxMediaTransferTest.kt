package com.helix.app.proot

import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.runtime.proot.core.JobArchiveException
import com.helix.runtime.proot.core.JobArchiveLimits
import com.helix.runtime.proot.core.ZipJobExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.file.Files

class LinuxMediaTransferTest {
    @Test
    fun streamedMediaBeyondPreviewCapRoundtrips() =
        fixture { root, store ->
            File(root, "z.mp4").writeBytes(ByteArray(2 * 1024 * 1024) { (it % 251).toByte() })
            File(root, "a.srt").writeText("captions")
            val archive = File(root, "scratch/input.zip")
            val hash = LinuxInputSnapshot(store).build(listOf("scope:test:z.mp4", "scope:test:a.srt"), archive)
            val extraction = ZipJobExtractor.extract(archive, File(root, "extracted"))
            assertEquals(hash, extraction.manifestSha256)
            assertEquals(listOf("a.srt-2", "z.mp4"), extraction.manifest.entries.map { it.path })
            assertEquals(File(root, "z.mp4").length(), File(root, "extracted/z.mp4").length())
            val leftovers = requireNotNull(archive.parentFile).listFiles().orEmpty()
            assertFalse(leftovers.any { it.name.startsWith("input-stream-") })
        }

    @Test
    fun perFileLimitCheckedBeforeCopying() =
        fixture { root, store ->
            RandomAccessFile(File(root, "large.mp4"), "rw").use {
                it.setLength(JobArchiveLimits.MAX_SINGLE_FILE_BYTES + 1)
            }
            val archive = File(root, "scratch/input.zip")
            assertThrows(JobArchiveException::class.java) {
                LinuxInputSnapshot(store).build(listOf("scope:test:large.mp4"), archive)
            }
            assertFalse(archive.exists())
            assertTrue(requireNotNull(archive.parentFile).listFiles().orEmpty().isEmpty())
        }

    @Test
    fun cancelledSnapshotCleansOwnedFiles() =
        fixture { root, store ->
            File(root, "video.mp4").writeBytes(ByteArray(1024 * 1024))
            val archive = File(root, "scratch/input.zip")
            var calls = 0
            assertThrows(InterruptedIOException::class.java) {
                LinuxInputSnapshot(store).build(listOf("scope:test:video.mp4"), archive) { ++calls > 2 }
            }
            assertFalse(archive.exists())
            assertTrue(requireNotNull(archive.parentFile).listFiles().orEmpty().isEmpty())
        }

    @Test
    fun rejectsEmptyAndDirectorySources() =
        fixture { root, store ->
            File(root, "empty").writeBytes(byteArrayOf())
            File(root, "directory").mkdir()
            for (name in listOf("empty", "directory")) {
                assertThrows(JobArchiveException::class.java) {
                    LinuxInputSnapshot(store).build(listOf("scope:test:$name"), File(root, "scratch/input.zip"))
                }
            }
        }

    private fun fixture(block: (File, WorkspaceArtifactStore) -> Unit) {
        val root = Files.createTempDirectory("linux-media-input-").toFile().canonicalFile
        try {
            block(root, WorkspaceArtifactStore(ScopeRootResolver { root.toPath() }))
        } finally {
            root.deleteRecursively()
        }
    }
}
