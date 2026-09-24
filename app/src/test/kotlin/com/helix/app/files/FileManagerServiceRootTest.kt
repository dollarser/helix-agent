package com.helix.app.files

import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path

class FileManagerServiceRootTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val workspaceScopeId = "ws"
    private lateinit var workspaceRoot: Path
    private lateinit var store: WorkspaceArtifactStore
    private lateinit var resolver: ScopeRootResolver

    @Before
    fun setUp() {
        workspaceRoot = tmp.newFolder("ws").toPath()
        resolver = ScopeRootResolver { _ -> workspaceRoot }
        store = WorkspaceArtifactStore(resolver)
        store.ensureLayout(workspaceScopeId)
    }

    private class FakeRootFileOperations(
        var granted: Boolean = true,
        var supported: Boolean = true,
    ) : RootFileOperations {
        override val isSupported: Boolean get() = supported

        override fun isRootGranted(): Boolean = granted

        override fun requestRoot(): Boolean {
            granted = true
            return true
        }

        val entries = mutableListOf<FileManagerService.FileEntry>()
        val backend = FakeRootBackend()

        override fun list(relativePath: String): List<FileManagerService.FileEntry> = entries

        override fun stat(relativePath: String): ManualFileInfo? =
            entries.firstOrNull { it.relativePath == relativePath }?.let {
                ManualFileInfo(it.isDirectory, it.sizeBytes)
            }

        override fun previewText(
            relativePath: String,
            maxBytes: Long,
        ): String? = "root-text-$relativePath"

        override fun previewImageBytes(
            relativePath: String,
            maxBytes: Long,
        ): ByteArray = byteArrayOf(1, 2, 3)

        override fun mimeTypeFor(relativePath: String): String = "text/plain"

        override fun fileInfo(
            relativePath: String,
            maxHashBytes: Long,
        ): FileManagerService.FileMeta = FileManagerService.FileMeta(100L, 1000L, "text/plain", true, "fake-sha", false)

        override fun realFileFor(
            relativePath: String,
            shareDir: File,
        ): File {
            val safeName = "staged-" + relativePath.replace('/', '_')
            val file = File(shareDir, safeName)
            file.writeText("shared-content")
            return file
        }

        override fun manualBackend(): ManualFileBackend = backend
    }

    private class FakeRootBackend : ManualFileBackend {
        val files = mutableMapOf<String, ByteArray>()
        val directories = mutableSetOf<String>()

        override fun validateMutation(path: String) {
            require(path.isNotBlank())
        }

        @Suppress("ReturnCount")
        override fun stat(path: String): ManualFileInfo? {
            if (path in directories) return ManualFileInfo(true, 0L)
            val bytes = files[path] ?: return null
            return ManualFileInfo(false, bytes.size.toLong())
        }

        override fun children(path: String): List<String> =
            files.keys
                .filter { it.startsWith("$path/") }
                .map { it.removePrefix("$path/") }
                .sorted()

        override fun read(path: String): InputStream = ByteArrayInputStream(files[path] ?: error("Not found: $path"))

        override fun create(
            path: String,
            directory: Boolean,
        ) {
            if (directory) directories.add(path) else files[path] = ByteArray(0)
        }

        override fun write(path: String): OutputStream =
            object : ByteArrayOutputStream() {
                override fun close() {
                    super.close()
                    files[path] = toByteArray()
                }
            }

        override fun rename(
            path: String,
            destination: String,
        ) {
            val bytes = files.remove(path) ?: error("Not found: $path")
            files[destination] = bytes
        }

        override fun delete(path: String) {
            files.remove(path)
            directories.remove(path)
        }
    }

    @Test
    fun rootSourceAppearsOnlyWhenGranted() {
        val fakeOps = FakeRootFileOperations(granted = false)
        val serviceNotGranted =
            FileManagerService(
                store = store,
                roots = resolver,
                workspaceScopeId = workspaceScopeId,
                rootOperations = fakeOps,
            )
        assertFalse(serviceNotGranted.sources().any { it.scopeId == FileManagerService.ROOT_SCOPE_ID })

        fakeOps.granted = true
        val serviceGranted =
            FileManagerService(
                store = store,
                roots = resolver,
                workspaceScopeId = workspaceScopeId,
                rootOperations = fakeOps,
            )
        val rootSource = serviceGranted.sources().firstOrNull { it.scopeId == FileManagerService.ROOT_SCOPE_ID }
        assertNotNull(rootSource)
        assertEquals(FileSourceKind.ROOT, rootSource?.kind)
        assertEquals(FileManagerService.ROOT_SCOPE_ID, rootSource?.scopeId)
    }

    @Test
    fun rootListingReturnsEntriesSorted() {
        val fakeOps = FakeRootFileOperations(granted = true)
        fakeOps.entries.addAll(
            listOf(
                FileManagerService.FileEntry("z_file.txt", "z_file.txt", false, 10L, 200L),
                FileManagerService.FileEntry("a_dir", "a_dir", true, 0L, 100L),
                FileManagerService.FileEntry("b_file.txt", "b_file.txt", false, 50L, 300L),
            ),
        )

        val service =
            FileManagerService(
                store = store,
                roots = resolver,
                workspaceScopeId = workspaceScopeId,
                rootOperations = fakeOps,
            )

        val listing = service.listing(FileManagerService.ROOT_SCOPE_ID, "", SortKey.NAME)
        assertEquals(3, listing.entries.size)
        // Directories sort before files:
        assertEquals("a_dir", listing.entries[0].name)
        assertTrue(listing.entries[0].isDirectory)
        assertEquals("b_file.txt", listing.entries[1].name)
        assertEquals("z_file.txt", listing.entries[2].name)
    }

    @Test
    fun rootListingFailsClosedWhenNotGranted() {
        val fakeOps = FakeRootFileOperations(granted = false)
        val service =
            FileManagerService(
                store = store,
                roots = resolver,
                workspaceScopeId = workspaceScopeId,
                rootOperations = fakeOps,
            )

        assertThrows(ScopeNotAvailable::class.java) {
            service.listing(FileManagerService.ROOT_SCOPE_ID, "")
        }
    }

    @Test
    fun rootPreviewAndFileInfoDelegation() {
        val fakeOps = FakeRootFileOperations(granted = true)
        val service =
            FileManagerService(
                store = store,
                roots = resolver,
                workspaceScopeId = workspaceScopeId,
                rootOperations = fakeOps,
            )

        val text = service.previewText(FileManagerService.ROOT_SCOPE_ID, "data/test.txt")
        assertEquals("root-text-data/test.txt", text)

        val image = service.previewImageBytes(FileManagerService.ROOT_SCOPE_ID, "data/test.png")
        assertEquals(3, image.size)

        val mime = service.mimeTypeFor(FileManagerService.ROOT_SCOPE_ID, "data/test.txt")
        assertEquals("text/plain", mime)

        val info = service.fileInfo(FileManagerService.ROOT_SCOPE_ID, "data/test.txt")
        assertEquals("fake-sha", info.sha256)
        assertEquals(100L, info.sizeBytes)

        val real = service.realFileFor(FileManagerService.ROOT_SCOPE_ID, "data/test.txt")
        assertTrue(real.exists())
        assertEquals("shared-content", real.readText())
    }

    @Test
    fun requestRootEnablesRootSource() {
        val fakeOps = FakeRootFileOperations(granted = false)
        val service =
            FileManagerService(
                store = store,
                roots = resolver,
                workspaceScopeId = workspaceScopeId,
                rootOperations = fakeOps,
            )

        assertFalse(service.isRootGranted)
        val res = service.requestRoot()
        assertTrue(res)
        assertTrue(service.isRootGranted)
        assertTrue(service.sources().any { it.scopeId == FileManagerService.ROOT_SCOPE_ID })
    }
}
