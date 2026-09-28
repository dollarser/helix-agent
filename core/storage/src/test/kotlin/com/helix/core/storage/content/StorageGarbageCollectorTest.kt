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

    @Test
    fun `symbolic links at root content and nested directory never reach external files`() {
        for (placement in listOf("root", "content", "nested")) {
            withTempRoot { root ->
                withTempRoot { outside ->
                    val valuable = File(outside, "user.tmp").apply { writeText("preserve") }
                    val link =
                        when (placement) {
                            "root" -> File(root, "linked-root")
                            "content" -> File(root, "content")
                            else -> File(root, "content/nested").also { it.parentFile.mkdirs() }
                        }
                    java.nio.file.Files
                        .createSymbolicLink(link.toPath(), outside.toPath())
                    try {
                        val result =
                            StorageGarbageCollector.collectGarbage(
                                if (placement == "root") link else root,
                                referenceChecker = { false },
                                gracePeriodMillis = 0,
                                now = System.currentTimeMillis() + 1000,
                            )
                        assertTrue("external file must survive $placement symlink", valuable.exists())
                        assertEquals(0L, result.freedBytes)
                    } finally {
                        java.nio.file.Files
                            .deleteIfExists(link.toPath())
                    }
                }
            }
        }
    }

    @Test
    fun `parent replaced during reference lookup is rechecked before deletion`() {
        withTempRoot { root ->
            withTempRoot { outside ->
                val ref = FileContentStore(root).write("same body")
                val original = File(root, ref.relativePath)
                val shard = original.parentFile
                val protected = File(outside, original.name).apply { writeText("same body") }
                try {
                    val result =
                        StorageGarbageCollector.collectGarbage(
                            root,
                            referenceChecker = {
                                check(shard.renameTo(File(root, "retained")))
                                java.nio.file.Files
                                    .createSymbolicLink(shard.toPath(), outside.toPath())
                                false
                            },
                            gracePeriodMillis = 0,
                            now = System.currentTimeMillis() + 1000,
                        )
                    assertTrue(protected.exists())
                    assertEquals(0L, result.freedBytes)
                } finally {
                    if (java.nio.file.Files
                            .isSymbolicLink(shard.toPath())
                    ) {
                        java.nio.file.Files
                            .delete(shard.toPath())
                    }
                }
            }
        }
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
