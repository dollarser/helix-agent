package com.helix.app.proot

import com.helix.runtime.proot.core.JobArchiveException
import com.helix.runtime.proot.core.JobManifestEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ProotProducedFilesTest {
    @Test
    fun publishesMultipleOutputsButNotInputs() =
        fixture { root, verified ->
            val files =
                listOf(
                    entry(verified, "input.png", "input"),
                    entry(verified, "output/视频.mp4", "video"),
                    entry(verified, "output/thumbs/a.png", "image"),
                )
            val produced = mutableListOf<ProotProducedFiles.Output>()
            ProotProducedFiles.publish(root, verified, "job_001122334455", files, register = produced::add)
            assertEquals(2, produced.size)
            assertEquals("video", File(root, "output/jobs/job_001122334455/视频.mp4").readText())
            assertFalse(File(root, "output/jobs/job_001122334455/input.png").exists())
        }

    @Test
    fun retryRegistersIdenticalFilesWithoutReplacement() =
        fixture { root, verified ->
            val entries = listOf(entry(verified, "output/a.png", "content"))
            var registrations = 0
            repeat(2) { ProotProducedFiles.publish(root, verified, "job_001122334455", entries) { registrations++ } }
            assertEquals(2, registrations)
            assertEquals("content", File(root, "output/jobs/job_001122334455/a.png").readText())
        }

    @Test
    fun registrationFailurePreservesFileForRetry() =
        fixture { root, verified ->
            val entries = listOf(entry(verified, "output/a.png", "content"))
            assertThrows(IllegalStateException::class.java) {
                ProotProducedFiles.publish(root, verified, "job_001122334455", entries) { error("registration failed") }
            }
            assertTrue(File(root, "output/jobs/job_001122334455/a.png").isFile)
            var registered = false
            ProotProducedFiles.publish(root, verified, "job_001122334455", entries) { registered = true }
            assertTrue(registered)
        }

    @Test
    fun recollectionNeverOverwritesUserEdits() =
        fixture { root, verified ->
            val entries = listOf(entry(verified, "output/a.png", "content"))
            ProotProducedFiles.publish(root, verified, "job_001122334455", entries) {}
            val target = File(root, "output/jobs/job_001122334455/a.png")
            target.writeText("user edit")
            assertThrows(IllegalStateException::class.java) {
                ProotProducedFiles.publish(root, verified, "job_001122334455", entries) {}
            }
            assertEquals("user edit", target.readText())
        }

    @Test
    fun corruptExtractedFileFailsBeforePublication() =
        fixture { root, verified ->
            val good = entry(verified, "output/a.png", "content")
            File(verified, good.path).writeText("tamper")
            assertThrows(IllegalStateException::class.java) {
                ProotProducedFiles.publish(root, verified, "job_001122334455", listOf(good)) {}
            }
            assertFalse(File(root, "output").exists())
        }

    @Test
    fun rejectsTraversalAndTargetSymlinks() =
        fixture { root, verified ->
            val entry = entry(verified, "output/a.png", "content")
            assertThrows(JobArchiveException::class.java) {
                val invalid = listOf(entry.copy(path = "output/../escape"))
                ProotProducedFiles.publish(root, verified, "job_001122334455", invalid) {}
            }
            Files.createSymbolicLink(File(root, "output").toPath(), verified.toPath())
            assertThrows(com.helix.core.workspace.SymlinkInPath::class.java) {
                ProotProducedFiles.publish(root, verified, "job_001122334455", listOf(entry)) {}
            }
        }

    @Test
    fun revokedPermissionPreventsCreatingOutput() =
        fixture { root, verified ->
            val entries = listOf(entry(verified, "output/a.png", "content"))
            assertThrows(IllegalStateException::class.java) {
                ProotProducedFiles.publish(root, verified, "job_001122334455", entries, { error("revoked") }) {}
            }
            assertFalse(File(root, "output").exists())
        }

    @Test
    fun revokedPermissionAfterCopyPreventsPublication() =
        fixture { root, verified ->
            val entries = listOf(entry(verified, "output/a.png", "content"))
            var checks = 0
            assertThrows(IllegalStateException::class.java) {
                ProotProducedFiles.publish(root, verified, "job_001122334455", entries, { check(++checks < 2) }) {}
            }
            val directory = File(root, "output/jobs/job_001122334455")
            assertFalse(File(directory, "a.png").exists())
            assertTrue(directory.listFiles().orEmpty().isEmpty())
        }

    private fun entry(
        root: File,
        path: String,
        text: String,
    ): JobManifestEntry {
        val file = File(root, path)
        requireNotNull(file.parentFile).mkdirs()
        file.writeText(text)
        return JobManifestEntry(path, LinuxRunTool.sha256Hex(file.readBytes()), file.length())
    }

    private fun fixture(block: (File, File) -> Unit) {
        val area = Files.createTempDirectory("produced-files-").toFile().canonicalFile
        val root = File(area, "workspace").also { it.mkdir() }
        val verified = File(area, "verified").also { it.mkdir() }
        try {
            block(root, verified)
        } finally {
            area.deleteRecursively()
        }
    }
}
