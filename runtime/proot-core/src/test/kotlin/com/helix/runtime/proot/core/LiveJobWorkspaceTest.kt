package com.helix.runtime.proot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class LiveJobWorkspaceTest {
    @Test fun writingOriginalDirectoryContentsDoesNotChangeItsIdentity() {
        val root =
            java.nio.file.Files
                .createTempDirectory("live-identity")
                .toFile()
                .canonicalFile
        try {
            val witness = LiveJobWorkspace.witness(root)
            java.io.File(root, "document.txt").writeText("edited in place")
            java.io.File(root, "nested").mkdir()
            LiveJobWorkspace.verify(root, witness)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun missingFilesAndObservationTimeoutAreExplicitlyUnavailableWithoutLosingOtherOutput() {
        val directory = temporary.newFolder("project").canonicalFile
        File(directory, "file").writeText("retained")
        assertNull(LiveJobWorkspace.observeAvailable(directory, "missing", Long.MAX_VALUE) { false })
        assertNull(LiveJobWorkspace.observeAvailable(directory, "file", 0) { false })
        assertEquals("retained", File(directory, "file").readText())
    }

    @Test fun cancellationIsNotHiddenAsAnUnavailableArtifact() {
        val directory = temporary.newFolder("project").canonicalFile
        File(directory, "file").writeText("retained")
        assertThrows(java.io.InterruptedIOException::class.java) {
            LiveJobWorkspace.observeAvailable(directory, "file", Long.MAX_VALUE) { true }
        }
    }

    @Test fun observationCancellationAndDeadlineLeaveTheOriginalFileUntouched() {
        val directory = temporary.newFolder("original").canonicalFile
        val file = File(directory, "report.txt").apply { writeText("retained") }
        assertThrows(java.io.InterruptedIOException::class.java) {
            LiveJobWorkspace.observe(directory, "report.txt", Long.MAX_VALUE) { true }
        }
        assertThrows(java.io.InterruptedIOException::class.java) {
            LiveJobWorkspace.observe(directory, "report.txt", 0)
        }
        val entry = LiveJobWorkspace.observe(directory, "report.txt", System.nanoTime() + 1_000_000_000)
        assertEquals(file.length(), entry.size)
        assertEquals("retained", file.readText())
    }

    @get:Rule val temporary = TemporaryFolder()

    @Test fun inPlaceOutputIsTheOriginalFileWithoutCopyOrOutputDirectory() {
        val directory = temporary.newFolder("project").canonicalFile
        val witness = LiveJobWorkspace.witness(directory)
        File(directory, "src").mkdirs()
        LiveJobWorkspace.resolve(directory, "src/main.py").writeText("print(1)")
        LiveJobWorkspace.verify(directory, witness)
        assertEquals("print(1)", File(directory, "src/main.py").readText())
    }

    @Test fun aReplacedDirectoryCannotReuseTheOriginalBinding() {
        val directory = temporary.newFolder("project").canonicalFile
        val witness = LiveJobWorkspace.witness(directory)
        Files.move(directory.toPath(), File(directory.parentFile, "old").toPath())
        directory.mkdir()
        assertThrows(IllegalArgumentException::class.java) { LiveJobWorkspace.verify(directory, witness) }
    }

    @Test fun linkedParentsAndTraversalCannotRetargetOutput() {
        val directory = temporary.newFolder("project").canonicalFile
        val outside = temporary.newFolder("outside").canonicalFile
        Files.createSymbolicLink(File(directory, "linked").toPath(), outside.toPath())
        assertThrows(IllegalArgumentException::class.java) { LiveJobWorkspace.resolve(directory, "linked/file") }
        assertThrows(JobArchiveException::class.java) { LiveJobWorkspace.resolve(directory, "../file") }
    }

    @Test fun referenceManifestCanDescribeLargeFilesWithoutLooseningArchiveTransferCaps() {
        val manifest =
            JobManifestCodec.encode(
                JobManifest(
                    listOf(
                        JobManifestEntry(
                            "large.mp4",
                            "a".repeat(64),
                            JobArchiveLimits.MAX_SINGLE_FILE_BYTES + 1,
                        ),
                    ),
                ),
            )
        assertThrows(JobArchiveException::class.java) { JobManifestCodec.parse(manifest) }
        assertEquals(
            JobArchiveLimits.MAX_SINGLE_FILE_BYTES + 1,
            JobManifestCodec
                .parse(manifest, Long.MAX_VALUE)
                .entries
                .single()
                .size,
        )
    }
}
