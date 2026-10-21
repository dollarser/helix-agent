package com.helix.app.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.nio.file.Files

class ShareSnapshotCacheTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun source(
        text: String,
        name: String = "note.txt",
    ) = ShareSnapshotCache.Source(name) { text.byteInputStream() }

    @Test fun longDisplayNameDoesNotExceedFilesystemNameLimit() {
        val root = temporary.newFolder()
        val snapshot = ShareSnapshotCache(root).stage(listOf(source("body", "n".repeat(251) + ".txt")), 100)
        assertEquals("txt", snapshot.files.single().extension)
        assertEquals("body", snapshot.files.single().readText())
    }

    @Test fun snapshotIsIndependentOfSubsequentSourceWrites() {
        val original = temporary.newFile("original.txt").apply { writeText("before") }
        val cache = temporary.newFolder("cache")
        val snapshot =
            ShareSnapshotCache(
                cache,
            ).stage(listOf(ShareSnapshotCache.Source("original.txt", original::inputStream)), 100)
        original.writeText("after")
        assertEquals("before", snapshot.files.single().readText())
        assertEquals("after", original.readText())
        assertEquals("text/plain", ShareSnapshotCache.mime(snapshot.files.single()))
    }

    @Test fun byteLimitCleansOnlyTheFailedCopy() {
        val root = temporary.newFolder()
        val cache = ShareSnapshotCache(root)
        val first = cache.stage(listOf(source("kept")), 100)
        assertThrows(IOException::class.java) { cache.stage(listOf(source("too large")), 2) }
        assertTrue(first.files.single().exists())
        assertEquals(1, root.listFiles()!!.size)
    }

    @Test fun cancellationCleansPartialTransportData() {
        val root = temporary.newFolder()
        assertThrows(ShareCancelled::class.java) {
            ShareSnapshotCache(root).stage(listOf(source("cancel")), 100) { true }
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }

    @Test fun expiryRemovesOnlyOwnedOldCopiesAndNeverFollowsLinks() {
        val root = temporary.newFolder()
        val cache = ShareSnapshotCache(root)
        val old = cache.stage(listOf(source("old")), 100)
        val unrelated =
            java.io.File(root, "handoff-user-files").apply {
                mkdir()
                setLastModified(0)
            }
        val external = temporary.newFolder("external").resolve("keep.txt").apply { writeText("keep") }
        Files.createSymbolicLink(old.directory.toPath().resolve("link"), external.toPath())
        assertTrue(old.directory.setLastModified(System.currentTimeMillis() - 90_000_000))
        cache.stage(listOf(source("new")), 100)
        assertFalse(old.directory.exists())
        assertTrue(unrelated.exists())
        assertEquals("keep", external.readText())
    }

    @Test fun traversalNamesAreRejectedWithoutLeavingCopies() {
        val root = temporary.newFolder()
        assertThrows(IllegalArgumentException::class.java) {
            ShareSnapshotCache(root).stage(listOf(source("x", "../escape")), 100)
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }
}
