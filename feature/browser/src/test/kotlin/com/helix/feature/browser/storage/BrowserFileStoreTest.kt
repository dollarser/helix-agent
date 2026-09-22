package com.helix.feature.browser.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class BrowserFileStoreTest {
    @Test
    fun failedCommitKeepsOldBytesAndRemovesTemporaryFile() {
        val dir = Files.createTempDirectory("browser-store").toFile()
        try {
            val file = dir.resolve("bookmarks.json").apply { writeText("old") }
            val store = BrowserFileStore { _, _ -> throw IOException("commit failed") }
            assertThrows(IOException::class.java) { store.write(file, "new") }
            assertEquals("old", file.readText())
            assertEquals(listOf("bookmarks.json"), dir.list()?.toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun byteLimitRejectsOversizedWritesAndReads() {
        val dir = Files.createTempDirectory("browser-bound").toFile()
        try {
            val file = dir.resolve("data.json").apply { writeText("old") }
            val store = BrowserFileStore()
            val oversized = "x".repeat(BrowserFileStore.MAX_BYTES + 1)
            assertThrows(IllegalArgumentException::class.java) { store.write(file, oversized) }
            assertEquals("old", store.read(file))
            file.writeText(oversized)
            assertThrows(IllegalArgumentException::class.java) { store.read(file) }
        } finally {
            dir.deleteRecursively()
        }
    }
}
