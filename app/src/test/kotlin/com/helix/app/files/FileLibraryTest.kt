package com.helix.app.files

import com.helix.core.workspace.FileScopePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class FileLibraryTest {
    @Test fun reopeningKeepsFavoriteIdentityWithoutCopyingFiles() {
        var persisted: String? = null
        val library = FileLibrary({ persisted }, { persisted = it })
        val path = FileScopePath("ws-original", "work/report.txt")
        library.toggleFavorite(path, false)
        library.visited(path, false)
        val reopened = FileLibrary({ persisted })
        reopened.load()
        assertEquals(
            path,
            reopened.state.value.favorites
                .single()
                .path,
        )
        assertEquals(
            path,
            reopened.state.value.recent
                .single()
                .path,
        )
    }

    @Test fun identicalNamesInDifferentScopesNeverCollapse() {
        val library = FileLibrary()
        val first = FileScopePath("ws-first", "work/report.txt")
        val second = FileScopePath("ws-second", "work/report.txt")
        library.visited(first, false)
        library.visited(second, false)
        library.visited(first, false)
        assertEquals(
            listOf(first, second),
            library.state.value.recent
                .map { it.path },
        )
    }

    @Test fun recentEvictionDoesNotRemoveFavorites() {
        val library = FileLibrary()
        val favorite = FileScopePath("app", "work/old.txt")
        library.toggleFavorite(favorite, false)
        library.visited(favorite, false)
        repeat(FileLibrary.RECENT_LIMIT + 1) { library.visited(FileScopePath("app", "work/$it.txt"), false) }
        assertEquals(FileLibrary.RECENT_LIMIT, library.state.value.recent.size)
        assertFalse(
            library.state.value.recent
                .any { it.path == favorite },
        )
        assertEquals(
            favorite,
            library.state.value.favorites
                .single()
                .path,
        )
    }

    @Test fun failedPersistenceDoesNotPublishSuccess() {
        val library = FileLibrary(write = { throw IOException("disk full") })
        assertThrows(IOException::class.java) { library.toggleFavorite(FileScopePath("app", "work"), true) }
        assertTrue(
            library.state.value.favorites
                .isEmpty(),
        )
    }

    @Test fun unknownVersionAndCorruptionAreNotOverwritten() {
        listOf("not json", "{\"version\":2}").forEach { raw ->
            var writes = 0
            val library = FileLibrary({ raw }, { writes++ })
            assertThrows(Exception::class.java) { library.visited(FileScopePath("app", "work/a"), false) }
            assertEquals(0, writes)
        }
    }

    @Test fun forgettingAnUnavailableReferenceNeedsNoFilesystemOrGrant() {
        val library = FileLibrary()
        val path = FileScopePath("ws-deleted", "work/missing.txt")
        library.visited(path, false)
        library.toggleFavorite(path, false)
        library.forget(
            library.state.value.favorites
                .single(),
        )
        assertTrue(
            library.state.value.favorites
                .isEmpty(),
        )
        assertTrue(
            library.state.value.recent
                .isEmpty(),
        )
    }

    @Test fun removingFavoritePreservesRecentHistory() {
        val library = FileLibrary()
        val path = FileScopePath("app", "work")
        library.visited(path, true)
        library.toggleFavorite(path, true)
        library.toggleFavorite(path, true)
        assertTrue(
            library.state.value.favorites
                .isEmpty(),
        )
        assertEquals(
            path,
            library.state.value.recent
                .single()
                .path,
        )
    }

    @Test fun favoriteLimitDoesNotEvictExistingUserChoices() {
        val library = FileLibrary()
        repeat(FileLibrary.FAVORITE_LIMIT) { library.toggleFavorite(FileScopePath("app", "work/$it"), true) }
        val before = library.state.value
        assertThrows(IllegalStateException::class.java) {
            library.toggleFavorite(FileScopePath("app", "work/extra"), true)
        }
        assertEquals(before, library.state.value)
    }

    @Test fun simultaneousOpensDoNotLoseDistinctRecords() {
        val library = FileLibrary()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val jobs =
                (0 until 20).map { index ->
                    pool.submit { library.visited(FileScopePath("app", "work/$index"), false) }
                }
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(20, library.state.value.recent.size)
        } finally {
            pool.shutdownNow()
        }
    }
}
