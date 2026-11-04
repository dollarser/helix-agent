package com.helix.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.UUID

class ExportStagingCleanupTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = 200_000_000L

    private fun staging(
        root: Path,
        age: Long,
    ): Path {
        val dir = Files.createDirectory(root.resolve(UUID.randomUUID().toString()))
        Files.writeString(dir.resolve("export.jsonl"), "synthetic")
        Files.setLastModifiedTime(dir, FileTime.fromMillis(now - age))
        return dir
    }

    @Test fun cleansOnlyExpiredUnreferencedOwnedDirectories() {
        val root = temp.newFolder().toPath()
        val expired = staging(root, 86_400_000)
        val active = staging(root, 90_000_000)
        val fresh = staging(root, 1)
        val foreign = Files.createDirectory(root.resolve("user-files"))
        Files.writeString(foreign.resolve("keep.txt"), "keep")
        assertEquals(1, ExportStagingCleanup.collect(root, active.fileName.toString(), now))
        assertFalse(Files.exists(expired))
        assertTrue(Files.exists(active))
        assertTrue(Files.exists(fresh))
        assertEquals("keep", Files.readString(foreign.resolve("keep.txt")))
    }

    @Test fun linksDoNotDeleteExternalFilesAndNoExternalTargetIsNeeded() {
        val root = temp.newFolder().toPath()
        val outside = temp.newFolder().toPath()
        Files.writeString(outside.resolve("keep.txt"), "keep")
        val expired = staging(root, 90_000_000)
        Files.createSymbolicLink(expired.resolve("link"), outside)
        Files.setLastModifiedTime(expired, FileTime.fromMillis(now - 90_000_000))
        Files.createSymbolicLink(root.resolve(UUID.randomUUID().toString()), outside)
        assertEquals(1, ExportStagingCleanup.collect(root, null, now))
        assertEquals("keep", Files.readString(outside.resolve("keep.txt")))
    }
}
