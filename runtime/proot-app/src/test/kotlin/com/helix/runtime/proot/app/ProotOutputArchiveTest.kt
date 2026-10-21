package com.helix.runtime.proot.app

import com.helix.runtime.proot.core.JobArchiveException
import com.helix.runtime.proot.core.JobArchiveLimits
import com.helix.runtime.proot.core.JobManifestCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.zip.ZipFile

class ProotOutputArchiveTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun transfersOriginalDeliverablesAndStreamsWithoutInputsOrIntermediates() {
        val workspace = temporary.newFolder("workspace")
        File(workspace, "input.txt").writeText("private input")
        File(workspace, "intermediate.bin").writeText("temporary")
        File(workspace, "output/nested").mkdirs()
        val document = File(workspace, "output/nested/report.docx")
        document.writeBytes(byteArrayOf(0, 1, 2, -1))
        File(workspace, "result.txt").writeText("explicit result")
        val stdout = temporary.newFile("stdout").apply { writeText("hello") }
        val stderr = temporary.newFile("stderr").apply { writeText("warning") }
        val archive = temporary.newFile("result.zip")
        val manifest = buildOutputArchive(archive, workspace, "stdout.txt" to stdout, "stderr.txt" to stderr)
        assertEquals(
            listOf("output/nested/report.docx", "result.txt", "stderr.txt", "stdout.txt"),
            JobManifestCodec.parse(manifest).entries.map { it.path },
        )
        ZipFile(archive).use { zip ->
            assertEquals(
                document.readBytes().toList(),
                zip.getInputStream(zip.getEntry("output/nested/report.docx")).readBytes().toList(),
            )
            assertEquals("hello", zip.getInputStream(zip.getEntry("stdout.txt")).reader().readText())
        }
    }

    @Test fun commandWithoutFilesTransfersOnlyCapturedStreams() {
        val workspace = temporary.newFolder("workspace")
        File(workspace, "input").writeText("not a deliverable")
        val stdout = temporary.newFile("stdout")
        val archive = temporary.newFile("result.zip")
        val manifest = buildOutputArchive(archive, workspace, "stdout.txt" to stdout)
        assertEquals(listOf("stdout.txt"), JobManifestCodec.parse(manifest).entries.map { it.path })
    }

    @Test fun rejectsLinkedOutputInsteadOfPublishingOutsideFiles() {
        val workspace = temporary.newFolder("workspace")
        val outside = temporary.newFolder("outside")
        File(outside, "secret").writeText("must not escape")
        Files.createSymbolicLink(File(workspace, "output").toPath(), outside.toPath())
        assertThrows(JobArchiveException::class.java) {
            buildOutputArchive(temporary.newFile("result.zip"), workspace)
        }
    }

    @Test fun rejectsLinkedFileInsideOutput() {
        val workspace = temporary.newFolder("workspace")
        val output = File(workspace, "output").apply { mkdirs() }
        val outside = temporary.newFile("outside")
        Files.createSymbolicLink(File(output, "linked").toPath(), outside.toPath())
        assertThrows(JobArchiveException::class.java) {
            buildOutputArchive(temporary.newFile("result.zip"), workspace)
        }
    }

    @Test fun excludedLargeInputDoesNotFailResultDeliveryButLargeDeliverableDoes() {
        val workspace = temporary.newFolder("workspace")
        val input = File(workspace, "input.bin")
        RandomAccessFile(input, "rw").use { it.setLength(JobArchiveLimits.MAX_SINGLE_FILE_BYTES + 1) }
        val archive = temporary.newFile("result.zip")
        assertEquals(
            emptyList<String>(),
            JobManifestCodec.parse(buildOutputArchive(archive, workspace)).entries.map {
                it.path
            },
        )
        val output = File(workspace, "output").apply { mkdirs() }
        Files.move(input.toPath(), File(output, "large.bin").toPath())
        assertThrows(JobArchiveException::class.java) { buildOutputArchive(archive, workspace) }
    }

    @Test fun rejectsLinkedResultFile() {
        val workspace = temporary.newFolder("workspace")
        Files.createSymbolicLink(File(workspace, "result.txt").toPath(), temporary.newFile("outside").toPath())
        assertThrows(JobArchiveException::class.java) {
            buildOutputArchive(temporary.newFile("result.zip"), workspace)
        }
    }
}
