package com.helix.core.storage.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StorageGarbageCollectorTest {
    @Test
    fun `referenced content file is preserved regardless of age`() {
        withTempRoot { root ->
            val store = FileContentStore(root)
            val ref = store.write("actively referenced body")
            val file = File(root, ref.relativePath)
            val pastTime = System.currentTimeMillis() - 7200_000L
            file.setLastModified(pastTime)

            val result =
                StorageGarbageCollector.collectGarbage(
                    contentRoot = root,
                    referenceChecker = { it == ref.toStorageString() },
                    gracePeriodMillis = 3600_000L,
                    now = System.currentTimeMillis(),
                )

            assertEquals(0, result.deletedContentFiles)
            assertEquals(0, result.deletedTempFiles)
            assertTrue(file.exists())
        }
    }

    @Test
    fun `unreferenced content file older than grace period is deleted`() {
        withTempRoot { root ->
            val store = FileContentStore(root)
            val ref = store.write("orphaned message body")
            val file = File(root, ref.relativePath)
            val now = System.currentTimeMillis()
            file.setLastModified(now - 7200_000L)

            val result =
                StorageGarbageCollector.collectGarbage(
                    contentRoot = root,
                    referenceChecker = { false },
                    gracePeriodMillis = 3600_000L,
                    now = now,
                )

            assertEquals(1, result.deletedContentFiles)
            assertEquals(ref.size, result.freedBytes)
            assertFalse(file.exists())
        }
    }

    @Test
    fun `unreferenced content file within grace period is protected`() {
        withTempRoot { root ->
            val store = FileContentStore(root)
            val ref = store.write("freshly written body in progress")
            val file = File(root, ref.relativePath)
            val now = System.currentTimeMillis()
            file.setLastModified(now - 60_000L) // 1 minute ago

            val result =
                StorageGarbageCollector.collectGarbage(
                    contentRoot = root,
                    referenceChecker = { false },
                    gracePeriodMillis = 3600_000L, // 1 hour grace
                    now = now,
                )

            assertEquals(0, result.deletedContentFiles)
            assertEquals(0L, result.freedBytes)
            assertTrue(file.exists())
        }
    }

    @Test
    fun `expired temp files are deleted while fresh temp files are preserved`() {
        withTempRoot { root ->
            val contentDir = File(root, "content/ab").apply { mkdirs() }
            val expiredTemp = File(contentDir, "abcdef.tmp-old").apply { writeText("expired temp bytes") }
            val freshTemp = File(contentDir, "abcdef.tmp-fresh").apply { writeText("fresh temp bytes") }
            val now = System.currentTimeMillis()

            expiredTemp.setLastModified(now - 5000_000L)
            freshTemp.setLastModified(now - 10_000L)

            val result =
                StorageGarbageCollector.collectGarbage(
                    contentRoot = root,
                    referenceChecker = { false },
                    gracePeriodMillis = 3600_000L,
                    now = now,
                )

            assertEquals(1, result.deletedTempFiles)
            assertFalse(expiredTemp.exists())
            assertTrue(freshTemp.exists())
        }
    }

    @Test
    fun `non existent root returns empty result gracefully`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "helix-missing-${System.nanoTime()}")
        val result =
            StorageGarbageCollector.collectGarbage(
                contentRoot = missing,
                referenceChecker = { false },
            )
        assertEquals(0, result.scannedFiles)
        assertEquals(0, result.deletedContentFiles)
    }

    private inline fun withTempRoot(block: (File) -> Unit) {
        val root = File(System.getProperty("java.io.tmpdir"), "helix-gc-test-${System.nanoTime()}")
        check(root.mkdirs()) { "cannot create temp root: ${root.absolutePath}" }
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
