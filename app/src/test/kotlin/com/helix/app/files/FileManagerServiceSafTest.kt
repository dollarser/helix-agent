package com.helix.app.files

import com.helix.app.R
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafTreeFileEntry
import com.helix.feature.files.SafTreeGrantCheck
import com.helix.feature.files.SafTreeGrantFacts
import com.helix.feature.files.SafTreeReadLimitExceeded
import com.helix.feature.files.SafTreeReader
import com.helix.feature.files.SafTreeScopeAccess
import com.helix.feature.files.SafTreeScopeService
import com.helix.feature.files.SafTreeStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path

/**
 * HXA-057 (JVM gate): the file manager's SAF tree scope dispatch. SAF scopes are governed
 * (re-verified in real time before every browse) and read-only; the model/UI see only display names
 * + bounded metadata, never a document id or `content://` URI (doc 10). A fake [SafTreeReader]
 * stands in for the `DocumentsContract` backend (pinned on device by the instrumented suite).
 */
class FileManagerServiceSafTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val workspaceId = "ws"
    private lateinit var scopeRoot: Path
    private lateinit var store: WorkspaceArtifactStore
    private lateinit var safGrantStore: SafGrantStore

    private val tree = "content://host/tree/docs"
    private lateinit var safScope: String

    @Before
    fun setUp() {
        scopeRoot = tmp.newFolder("ws").toPath()
        store = WorkspaceArtifactStore(ScopeRootResolver { _ -> scopeRoot })
        store.ensureLayout(workspaceId)
        safGrantStore = SafGrantStore(tmp.newFolder("saf").toPath().resolve("g.json")) { 0L }
        // Assign the SAF scope id (derived from the tree) after the store exists.
        safScope = safGrantStore.deriveScopeId(tree)
    }

    private fun liveCheck(vararg liveTrees: String): SafTreeGrantCheck =
        SafTreeGrantCheck { uri ->
            if (uri in liveTrees) {
                SafTreeGrantFacts(
                    rootLive = true,
                    authority = uri.substringAfter("content://").substringBefore('/'),
                    rootDocumentId = uri.substringAfterLast('/'),
                    readable = true,
                    writable = false,
                )
            } else {
                null
            }
        }

    private fun makeService(
        check: SafTreeGrantCheck,
        reader: SafTreeReader,
    ): FileManagerService {
        val service = SafTreeScopeService(safGrantStore, check)
        val access = SafTreeScopeAccess(service, reader, tmp.newFolder("share").toPath())
        return FileManagerService(store, ScopeRootResolver { _ -> scopeRoot }, workspaceId, access)
    }

    // ── 来源列表: SAF scopes appear only while live ──────────────────────────────────────

    @Test
    fun aLiveSafScopeAppearsInTheSourceListReadOnly() {
        safGrantStore.grant(tree, "Docs")
        val service = makeService(liveCheck(tree), FakeReader(mapOf("a.txt" to "x".toByteArray()), emptySet()))
        val source = service.sources().first { it.scopeId == safScope }
        assertEquals(FileSourceKind.SAF, source.kind)
        assertEquals("Docs", source.displayName)
        assertFalse(source.supportsMutation)
    }

    @Test
    fun aDeadSafScopeIsOmittedFromTheSourceList() {
        safGrantStore.grant(tree, "Docs")
        val service = makeService(liveCheck(), FakeReader(emptyMap(), emptySet())) // nothing still answers
        assertFalse(service.sources().any { it.kind == FileSourceKind.SAF })
    }

    // ── browse (list) is re-verified + fail closed ───────────────────────────────────────

    @Test
    fun listSafDirectoryReturnsBoundedEntriesDirectoriesFirst() {
        safGrantStore.grant(tree, "Docs")
        val reader =
            FakeReader(
                mapOf("sub/note.txt" to "n".toByteArray(), "top.txt" to "t".toByteArray()),
                setOf("sub"),
            )
        val service = makeService(liveCheck(tree), reader)
        val entries = service.list(safScope, "")
        assertEquals(listOf("sub", "top.txt"), entries.map { it.name })
        assertTrue(entries.first { it.name == "sub" }.isDirectory)
        assertFalse(entries.any { it.name.contains("content://") || it.relativePath.contains("content://") })
    }

    @Test
    fun listSafDirectoryOfADeadGrantFailsClosed() {
        safGrantStore.grant(tree, "Docs")
        val service = makeService(liveCheck(), FakeReader(emptyMap(), emptySet()))
        assertThrows(ScopeNotAvailable::class.java) { service.list(safScope, "") }
    }

    // ── preview / info / mime / share ────────────────────────────────────────────────────

    @Test
    fun previewTextAndFileInfoWorkForATextSaFDocument() {
        safGrantStore.grant(tree, "Docs")
        val reader = FakeReader(mapOf("notes.txt" to "hello world".toByteArray()), emptySet())
        val service = makeService(liveCheck(tree), reader)
        assertEquals("hello world", service.previewText(safScope, "notes.txt"))
        val info = service.fileInfo(safScope, "notes.txt")
        assertEquals(11L, info.sizeBytes)
        // ContentProbe classifies by magic (octet-stream for magic-less text) but isText is true,
        // which is what previewText keys on.
        assertEquals("application/octet-stream", info.mimeType)
        assertTrue(info.isText)
        assertNotNull("a small SAF file is hashed", info.sha256)
    }

    @Test
    fun previewTextIsNullForABinarySaFDocument() {
        safGrantStore.grant(tree, "Docs")
        // A PNG magic prefix (binary → not text).
        val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D.toByte(), 0x0A, 0x1A, 0x0A)
        val service = makeService(liveCheck(tree), FakeReader(mapOf("img.png" to pngMagic), emptySet()))
        assertNull(service.previewText(safScope, "img.png"))
        assertTrue(service.previewImageBytes(safScope, "img.png").isNotEmpty())
    }

    @Test
    fun mimeTypeForReflectsTheBoundedPrefix() {
        safGrantStore.grant(tree, "Docs")
        // A PNG-magic document: the mime is driven by the bounded prefix the reader returns.
        val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D.toByte(), 0x0A, 0x1A, 0x0A)
        val service = makeService(liveCheck(tree), FakeReader(mapOf("img.png" to pngMagic), emptySet()))
        assertEquals("image/png", service.mimeTypeFor(safScope, "img.png"))
    }

    @Test
    fun shareStagesASafDocumentIntoTheAppPrivateDir() {
        safGrantStore.grant(tree, "Docs")
        val reader = FakeReader(mapOf("doc.txt" to "share me".toByteArray()), emptySet())
        val service = makeService(liveCheck(tree), reader)
        val staged = service.realFileFor(safScope, "doc.txt")
        assertEquals("share me", String(Files.readAllBytes(staged.toPath())))
        assertFalse("the staged name must not leak the URI", staged.name.contains("content://"))
    }

    @Test
    fun sharingATooLargeSaFDocumentFailsClosed() {
        safGrantStore.grant(tree, "Docs")
        // The reader's copy cap is hit before the service's (a 0-byte cap forces the limit).
        val reader =
            object : SafTreeReader {
                override fun list(
                    scopeId: String,
                    relativePath: String,
                ): List<SafTreeFileEntry> = emptyList()

                override fun read(
                    scopeId: String,
                    relativePath: String,
                    offset: Long,
                    maxBytes: Long,
                ): ByteArray = ByteArray(0)

                override fun stat(
                    scopeId: String,
                    relativePath: String,
                ): SafTreeStat = SafTreeStat(false, false, -1L, -1L)

                override fun copyToAppPrivate(
                    scopeId: String,
                    relativePath: String,
                    target: Path,
                    maxBytes: Long,
                ): Long = throw SafTreeReadLimitExceeded("exceeds the share cap")
            }
        val service = makeService(liveCheck(tree), reader)
        assertThrows(SafTreeReadLimitExceeded::class.java) { service.realFileFor(safScope, "doc.txt") }
    }

    // ── SAF scopes are read-only: mutations fail closed ──────────────────────────────────

    @Test
    fun mutationsOnASafScopeFailClosed() {
        safGrantStore.grant(tree, "Docs")
        val service = makeService(liveCheck(tree), FakeReader(emptyMap(), emptySet()))
        val expected = R.string.files_saf_read_only.toString() // HXA-069: facade now emits the stable resource id
        assertEquals(FileManagerService.FileOpResult.Error(expected), service.rename(safScope, "a", "b", false))
        assertEquals(FileManagerService.FileOpResult.Error(expected), service.copy(safScope, "a", "b", false))
        assertEquals(FileManagerService.FileOpResult.Error(expected), service.trash(safScope, "a"))
        assertEquals(FileManagerService.FileOpResult.Error(expected), service.makeDirectory(safScope, "", "new"))
    }

    /**
     * A minimal in-memory SAF tree reader (JVM stand-in for the `DocumentsContract` backend).
     * [files] is relativePath → bytes; [dirs] is the set of directory relative paths.
     */
    private class FakeReader(
        private val files: Map<String, ByteArray>,
        private val dirs: Set<String>,
    ) : SafTreeReader {
        private val all: List<String> get() = (files.keys + dirs).toList()

        override fun list(
            scopeId: String,
            relativePath: String,
        ): List<SafTreeFileEntry> {
            val base = if (relativePath.isEmpty()) "" else "$relativePath/"
            return all
                .asSequence()
                .filter { it.startsWith(base) }
                .map { it.removePrefix(base) }
                .filter { it.isNotEmpty() && !it.contains('/') }
                .distinct()
                .map { name ->
                    val full = base + name
                    val isDirectory = full in dirs
                    val size = if (isDirectory) -1L else (files[full]?.size ?: 0L).toLong()
                    SafTreeFileEntry(name, isDirectory, size, 123L)
                }.toList()
        }

        override fun read(
            scopeId: String,
            relativePath: String,
            offset: Long,
            maxBytes: Long,
        ): ByteArray {
            val bytes = files[relativePath] ?: throw FileNotFoundException("not found: $relativePath")
            val start = offset.toInt()
            if (start >= bytes.size) return ByteArray(0)
            return bytes.copyOfRange(start, minOf(bytes.size, start + maxBytes.toInt()))
        }

        override fun stat(
            scopeId: String,
            relativePath: String,
        ): SafTreeStat {
            if (relativePath in dirs) return SafTreeStat(true, true, -1L, 123L)
            val bytes = files[relativePath]
            return if (bytes == null) {
                SafTreeStat(false, false, -1L, -1L)
            } else {
                SafTreeStat(true, false, bytes.size.toLong(), 123L)
            }
        }

        override fun copyToAppPrivate(
            scopeId: String,
            relativePath: String,
            target: Path,
            maxBytes: Long,
        ): Long {
            val bytes = files[relativePath] ?: throw FileNotFoundException("not found: $relativePath")
            if (bytes.size > maxBytes) throw SafTreeReadLimitExceeded("exceeds the copy cap")
            Files.write(target, bytes)
            return bytes.size.toLong()
        }
    }
}
