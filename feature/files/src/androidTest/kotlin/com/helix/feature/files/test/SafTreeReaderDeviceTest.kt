package com.helix.feature.files.test

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.feature.files.ContentResolverSafTreeReader
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafTreeReadLimitExceeded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path

/**
 * HXA-057 (device gate): the REAL [ContentResolverSafTreeReader] (the SAF tree read-only browse
 * backend) driven through the REAL `ContentResolver` against the in-APK [TreeDocumentsProvider] —
 * the `DocumentsContract` child/document query URIs + `openInputStream`. Covers list / read / stat /
 * copy and the fail-closed boundaries: a missing path, an AMBIGUOUS display name, and a copy that
 * exceeds its cap.
 */
class SafTreeReaderDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver
    private val treeUri = "content://${TreeDocumentsProvider.AUTHORITY}/tree/root"

    private data class Fixture(
        val scopeId: String,
        val reader: ContentResolverSafTreeReader,
    )

    private fun fixture(tag: String): Fixture {
        val path =
            Files
                .createDirectories(context.cacheDir.toPath().resolve("saf-reader-it-$tag"))
                .resolve("g.json")
        val store = SafGrantStore(path) { 0L }
        val scopeId = store.grant(treeUri, "Tree").scopeId
        return Fixture(scopeId, ContentResolverSafTreeReader(resolver, store))
    }

    @Test
    fun listRootReturnsTheFixedTree() {
        val (scopeId, reader) = fixture("root")
        val entries = reader.list(scopeId, "")
        assertEquals(setOf("a.txt", "sub", "dup"), entries.map { it.name }.toSet())
        assertTrue(entries.single { it.name == "sub" }.isDirectory)
        assertTrue(entries.single { it.name == "dup" }.isDirectory)
        assertFalse(entries.single { it.name == "a.txt" }.isDirectory)
        assertEquals(5L, entries.single { it.name == "a.txt" }.sizeBytes)
    }

    @Test
    fun listSubdirectoryWalksByDisplayName() {
        val (scopeId, reader) = fixture("sub")
        val entries = reader.list(scopeId, "sub")
        assertEquals(listOf("b.txt"), entries.map { it.name })
    }

    @Test
    fun readsAFileAndANestedFile() {
        val (scopeId, reader) = fixture("read")
        assertEquals("hello", String(reader.read(scopeId, "a.txt", 0, 64)))
        assertEquals("x", String(reader.read(scopeId, "sub/b.txt", 0, 64)))
        // A bounded window with an offset: skip 2 bytes of "hello".
        assertEquals("llo", String(reader.read(scopeId, "a.txt", 2, 64)))
    }

    @Test
    fun statDistinguishesFileDirectoryAndMissing() {
        val (scopeId, reader) = fixture("stat")
        val file = reader.stat(scopeId, "a.txt")
        assertTrue(file.exists)
        assertFalse(file.isDirectory)
        assertEquals(5L, file.sizeBytes)

        val dir = reader.stat(scopeId, "sub")
        assertTrue(dir.exists)
        assertTrue(dir.isDirectory)

        val missing = reader.stat(scopeId, "nope")
        assertFalse(missing.exists)
    }

    @Test
    fun aMissingPathFailsClosedOnRead() {
        val (scopeId, reader) = fixture("missing")
        assertThrows(FileNotFoundException::class.java) { reader.read(scopeId, "nope", 0, 64) }
        assertThrows(FileNotFoundException::class.java) { reader.read(scopeId, "sub/nope", 0, 64) }
    }

    // An AMBIGUOUS display name (two children both "same.txt") fails closed rather than guessing.
    @Test
    fun anAmbiguousDisplayNameFailsClosed() {
        val (scopeId, reader) = fixture("ambiguous")
        assertThrows(FileNotFoundException::class.java) { reader.read(scopeId, "dup/same.txt", 0, 64) }
    }

    @Test
    fun copyToAppPrivateStagesTheBytes() {
        val (scopeId, reader) = fixture("copy")
        val target: Path = context.cacheDir.toPath().resolve("saf-copy-out.bin")
        val copied = reader.copyToAppPrivate(scopeId, "a.txt", target, 8L * 1024)
        assertEquals(5L, copied)
        assertEquals("hello", String(Files.readAllBytes(target)))
    }

    // A copy that exceeds its cap fails closed (deleting the partial target), never truncating.
    @Test
    fun aCopyExceedingItsCapFailsClosed() {
        val (scopeId, reader) = fixture("cap")
        val target: Path = context.cacheDir.toPath().resolve("saf-copy-cap.bin")
        assertThrows(SafTreeReadLimitExceeded::class.java) { reader.copyToAppPrivate(scopeId, "a.txt", target, 2L) }
        assertFalse("a failing copy leaves no partial file", Files.exists(target))
    }
}
