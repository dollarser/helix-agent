package com.helix.app.files

import com.helix.core.workspace.AtomicFileWriter
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.feature.files.SafCancelToken
import com.helix.feature.files.SafDestinationOpener
import com.helix.feature.files.SafDestinationVerifier
import com.helix.feature.files.SafExportPipeline
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafImportExportAccess
import com.helix.feature.files.SafImportPipeline
import com.helix.feature.files.SafSourceMetadata
import com.helix.feature.files.SafSourceOpener
import com.helix.feature.files.SafTreeDestination
import com.helix.feature.files.SafTreeFileEntry
import com.helix.feature.files.SafTreeGrantCheck
import com.helix.feature.files.SafTreeGrantFacts
import com.helix.feature.files.SafTreeImportEntry
import com.helix.feature.files.SafTreeLister
import com.helix.feature.files.SafTreeReader
import com.helix.feature.files.SafTreeScopeAccess
import com.helix.feature.files.SafTreeScopeService
import com.helix.feature.files.SafTreeStat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HXA-058 (JVM gate): the file manager's 导入/导出 entries. The REAL HXA-044 pipelines are driven
 * through the facade with fake platform seams (a lying/throwing source opener, a lying
 * destination verifier, a re-read seam, a tree lister / tree destination): the fail-closed
 * contract — lying providers, conflicts, cancellation, temp reclamation, verified semantics —
 * is pinned here on the JVM; the real ContentResolver adapters are pinned on device by the
 * instrumented suite.
 */
class FileManagerServiceTransferTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val workspaceId = "ws"
    private lateinit var scopeRoot: Path
    private lateinit var store: WorkspaceArtifactStore

    private val never = SafCancelToken { false }

    // Fake platform seams (configurable per test).
    private lateinit var sourceBytes: MutableMap<String, ByteArray>
    private lateinit var metadata: SafSourceMetadata
    private lateinit var sink: ByteSink
    private var reportedDestSize = -1L
    private var reReadBytes: ByteArray? = null
    private lateinit var listedTree: MutableList<SafTreeImportEntry>
    private lateinit var treeDest: FakeTreeDestination

    private lateinit var access: SafImportExportAccess
    private lateinit var service: FileManagerService

    private fun hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        scopeRoot = tmp.newFolder("ws").toPath()
        store = WorkspaceArtifactStore(ScopeRootResolver { _ -> scopeRoot })
        store.ensureLayout(workspaceId)

        sourceBytes = mutableMapOf()
        metadata = SafSourceMetadata(5L, null, "doc.txt")
        sink = ByteSink()
        reportedDestSize = -1L
        reReadBytes = null
        listedTree = mutableListOf()
        treeDest = FakeTreeDestination()

        val importPipeline =
            SafImportPipeline(
                ScopeRootResolver { _ -> scopeRoot },
                SafSourceOpener { uri ->
                    sourceBytes[uri]?.let { ByteArrayInputStream(it) } ?: throw SecurityException("source denied")
                },
            )
        val exportPipeline =
            SafExportPipeline(
                ScopeRootResolver { _ -> scopeRoot },
                SafDestinationOpener { sink },
                SafDestinationVerifier { reportedDestSize },
            )
        access =
            SafImportExportAccess(
                importPipeline = importPipeline,
                exportPipeline = exportPipeline,
                sourceMetadata = { metadata },
                treeLister =
                    object : SafTreeLister {
                        override fun listTree(treeUri: String): List<SafTreeImportEntry> = listedTree.toList()
                    },
                treeDestination = treeDest,
                destinationReReader = { reReadBytes?.let { ByteArrayInputStream(it) } },
            )
        service = FileManagerService(store, ScopeRootResolver { _ -> scopeRoot }, workspaceId, null, access)
    }

    // ── 导入: single document ─────────────────────────────────────────────────────────────

    @Test
    fun importSinglePublishesIntoInputUnderTheSanitizedName() {
        sourceBytes["content://p/doc"] = "hello import\n".toByteArray()
        metadata = SafSourceMetadata(13L, null, "../../etc/evil\u0000name.txt")

        val result = service.importSingleDocument("content://p/doc", ConflictPolicy.ASK, never) { _, _ -> }

        val item = result.items.single()
        assertEquals(TransferItemStatus.COMPLETED, item.status)
        assertEquals("input/.._.._etc_evilname.txt", item.targetLabel)
        assertEquals(13L, item.sizeBytes)
        assertEquals(hex("hello import\n".toByteArray()), item.sha256)
        assertEquals("hello import\n", String(Files.readAllBytes(scopeRoot.resolve("input/.._.._etc_evilname.txt"))))
        assertEquals(0, AtomicFileWriter.cleanup(scopeRoot))
    }

    @Test
    fun importSingleExistingTargetUnderAskReportsConflictWithoutOverwriting() {
        Files.write(scopeRoot.resolve("input/doc.txt"), "old".toByteArray())
        sourceBytes["content://p/doc"] = "new".toByteArray()
        metadata = SafSourceMetadata(3L, null, "doc.txt")

        val result = service.importSingleDocument("content://p/doc", ConflictPolicy.ASK, never) { _, _ -> }

        assertEquals(TransferItemStatus.CONFLICT, result.items.single().status)
        assertEquals("old", String(Files.readAllBytes(scopeRoot.resolve("input/doc.txt"))))
    }

    @Test
    fun importSingleExistingTargetUnderSkipSkips() {
        Files.write(scopeRoot.resolve("input/doc.txt"), "old".toByteArray())
        sourceBytes["content://p/doc"] = "new".toByteArray()
        metadata = SafSourceMetadata(3L, null, "doc.txt")

        val result = service.importSingleDocument("content://p/doc", ConflictPolicy.SKIP, never) { _, _ -> }

        assertEquals(TransferItemStatus.SKIPPED, result.items.single().status)
        assertEquals("old", String(Files.readAllBytes(scopeRoot.resolve("input/doc.txt"))))
    }

    @Test
    fun importSingleExistingTargetUnderRenameUsesTheNextAvailableName() {
        Files.write(scopeRoot.resolve("input/doc.txt"), "old".toByteArray())
        sourceBytes["content://p/doc"] = "new".toByteArray()
        metadata = SafSourceMetadata(3L, null, "doc.txt")

        val result = service.importSingleDocument("content://p/doc", ConflictPolicy.RENAME, never) { _, _ -> }

        val item = result.items.single()
        assertEquals(TransferItemStatus.COMPLETED, item.status)
        assertEquals("input/doc (1).txt", item.targetLabel)
        assertEquals("old", String(Files.readAllBytes(scopeRoot.resolve("input/doc.txt"))))
        assertEquals("new", String(Files.readAllBytes(scopeRoot.resolve("input/doc (1).txt"))))
    }

    @Test
    fun importSingleExistingTargetUnderOverwriteTrashesOldAndPublishesNew() {
        Files.write(scopeRoot.resolve("input/doc.txt"), "old".toByteArray())
        sourceBytes["content://p/doc"] = "new".toByteArray()
        metadata = SafSourceMetadata(3L, null, "doc.txt")

        val result = service.importSingleDocument("content://p/doc", ConflictPolicy.OVERWRITE, never) { _, _ -> }

        assertEquals(TransferItemStatus.COMPLETED, result.items.single().status)
        assertEquals("new", String(Files.readAllBytes(scopeRoot.resolve("input/doc.txt"))))
        assertTrue("the overwritten file goes to the trash (restorable)", service.listTrash(workspaceId).isNotEmpty())
    }

    @Test
    fun importSingleFromALyingSizeSourceFailsClosedAndPublishesNothing() {
        sourceBytes["content://p/doc"] = ByteArray(100) { it.toByte() }
        metadata = SafSourceMetadata(10L, null, "doc.bin")

        val result = service.importSingleDocument("content://p/doc", ConflictPolicy.ASK, never) { _, _ -> }

        val item = result.items.single()
        assertEquals(TransferItemStatus.FAILED, item.status)
        assertTrue("no file the user was not told about survives", !Files.exists(scopeRoot.resolve("input/doc.bin")))
        assertEquals(0, AtomicFileWriter.cleanup(scopeRoot))
    }

    @Test
    fun importSingleFromAnUnopenableSourceFailsClosed() {
        // The source map has no entry for this URI: the opener throws (a revoked grant shape).
        metadata = SafSourceMetadata(5L, null, "doc.txt")

        val result = service.importSingleDocument("content://p/revoked", ConflictPolicy.ASK, never) { _, _ -> }

        assertEquals(TransferItemStatus.FAILED, result.items.single().status)
        assertFalse(Files.exists(scopeRoot.resolve("input/doc.txt")))
    }

    @Test
    fun importSingleCancelledBeforeStartPublishesNothing() {
        sourceBytes["content://p/doc"] = "x".toByteArray()
        metadata = SafSourceMetadata(1L, null, "doc.txt")
        val alreadyCancelled = SafCancelToken { true }

        val result = service.importSingleDocument("content://p/doc", ConflictPolicy.ASK, alreadyCancelled) { _, _ -> }

        assertEquals(TransferItemStatus.CANCELLED, result.items.single().status)
        assertFalse(Files.exists(scopeRoot.resolve("input/doc.txt")))
    }

    @Test
    fun importSingleWithAMidCopyIoFailureFailsClosedWithoutTempLeftover() {
        sourceBytes["content://p/doc"] = "x".toByteArray()
        metadata = SafSourceMetadata(1L, null, "doc.txt")
        // A source stream that dies mid-copy: the pipeline must map it to a stable refusal.
        val dying =
            object : InputStream() {
                private val inner = ByteArrayInputStream("0123456789".toByteArray())
                private var served = 0

                override fun read(): Int {
                    served++
                    if (served == 3) throw IOException("stream reset")
                    return inner.read()
                }
            }
        val pipeline =
            SafImportPipeline(
                ScopeRootResolver { _ -> scopeRoot },
                SafSourceOpener { dying },
            )
        val svc =
            FileManagerService(
                store,
                ScopeRootResolver { _ -> scopeRoot },
                workspaceId,
                null,
                rebuilt(importPipeline = pipeline),
            )

        val result = svc.importSingleDocument("content://p/doc", ConflictPolicy.ASK, never) { _, _ -> }

        assertEquals(TransferItemStatus.FAILED, result.items.single().status)
        assertFalse(Files.exists(scopeRoot.resolve("input/doc.txt")))
        assertEquals(0, AtomicFileWriter.cleanup(scopeRoot))
    }

    @Test
    fun importSingleReclaimsAProcessKilledTempFirst() {
        sourceBytes["content://p/doc"] = "x".toByteArray()
        metadata = SafSourceMetadata(1L, null, "doc.txt")
        val orphan = scopeRoot.resolve("input/.helix-tmp-orphan")
        Files.write(orphan, "stale".toByteArray())

        val result = service.importSingleDocument("content://p/doc", ConflictPolicy.ASK, never) { _, _ -> }

        assertEquals(TransferItemStatus.COMPLETED, result.items.single().status)
        assertEquals("the crashed transfer's temp is reclaimed", 1, result.reclaimedTempFiles)
        assertFalse(Files.exists(orphan))
    }

    // ── 导入: folder (tree) ───────────────────────────────────────────────────────────────

    private fun treeEntry(
        bytes: ByteArray,
        uriSuffix: String,
        vararg segments: String,
    ): SafTreeImportEntry {
        val uri = "content://p/tree-$uriSuffix"
        sourceBytes[uri] = bytes
        return SafTreeImportEntry(segments.toList(), bytes.size.toLong(), uri)
    }

    @Test
    fun importTreeCopiesFilesWithStructureAndReportsEverySkip() {
        listedTree +=
            listOf(
                treeEntry("a".toByteArray(), "a", "a.txt"),
                treeEntry("b".toByteArray(), "b", "sub", "b.txt"),
                treeEntry("1".toByteArray(), "s1", "dup", "same.txt"),
                treeEntry("2".toByteArray(), "s2", "dup", "same.txt"),
                treeEntry("deep".toByteArray(), "deep", *(1..33).map { "d$it" }.toTypedArray(), "f.txt"),
            )

        val result = service.importTree("content://p/tree", ConflictPolicy.ASK, never) { _, _ -> }

        val bySource = result.items.associateBy { it.sourceLabel }
        assertEquals(TransferItemStatus.COMPLETED, bySource["a.txt"]!!.status)
        assertEquals("a", String(Files.readAllBytes(scopeRoot.resolve("input/a.txt"))))
        assertEquals(TransferItemStatus.COMPLETED, bySource["sub/b.txt"]!!.status)
        assertEquals("b", String(Files.readAllBytes(scopeRoot.resolve("input/sub/b.txt"))))
        assertEquals(TransferItemStatus.SKIPPED, bySource["dup/same.txt"]!!.status)
        val deep = (1..33).joinToString("/") { "d$it" } + "/f.txt"
        assertEquals(TransferItemStatus.SKIPPED, bySource[deep]!!.status)
        assertEquals(5, result.items.size)
    }

    @Test
    fun importTreeCancelledMidBatchLeavesCompletedDurableAndTheRestCancelled() {
        listedTree +=
            listOf(
                treeEntry("one".toByteArray(), "1", "one.txt"),
                treeEntry("two".toByteArray(), "2", "two.txt"),
                treeEntry("three".toByteArray(), "3", "three.txt"),
            )
        // The progress tick fires BEFORE each file, and the pipeline re-checks the cancel token
        // at each file start: cancelling on the third tick completes the first two files.
        val cancel = AtomicBoolean(false)
        var ticks = 0
        val result =
            service.importTree("content://p/tree", ConflictPolicy.ASK, SafCancelToken { cancel.get() }) { _, _ ->
                ticks++
                if (ticks >= 3) cancel.set(true)
            }

        val bySource = result.items.associateBy { it.sourceLabel }
        assertEquals(TransferItemStatus.COMPLETED, bySource["one.txt"]!!.status)
        assertEquals("one", String(Files.readAllBytes(scopeRoot.resolve("input/one.txt"))))
        assertEquals(TransferItemStatus.COMPLETED, bySource["two.txt"]!!.status)
        assertEquals(TransferItemStatus.CANCELLED, bySource["three.txt"]!!.status)
    }

    @Test
    fun importTreeWithAnExistingTargetUnderAskReportsPerItemConflict() {
        Files.write(scopeRoot.resolve("input/a.txt"), "old".toByteArray())
        listedTree +=
            listOf(
                treeEntry("a".toByteArray(), "a", "a.txt"),
                treeEntry("b".toByteArray(), "b", "b.txt"),
            )

        val result = service.importTree("content://p/tree", ConflictPolicy.ASK, never) { _, _ -> }

        val bySource = result.items.associateBy { it.sourceLabel }
        assertEquals(TransferItemStatus.CONFLICT, bySource["a.txt"]!!.status)
        assertEquals(TransferItemStatus.COMPLETED, bySource["b.txt"]!!.status)
        assertEquals("old", String(Files.readAllBytes(scopeRoot.resolve("input/a.txt"))))
    }

    @Test
    fun importTreeWithAnUnenumerableTreeFailsClosed() {
        listedTree = mutableListOf()
        val failing =
            rebuilt(
                treeLister =
                    object : SafTreeLister {
                        override fun listTree(treeUri: String): List<SafTreeImportEntry> =
                            throw IOException("grant revoked mid-operation")
                    },
            )
        val svc = FileManagerService(store, ScopeRootResolver { _ -> scopeRoot }, workspaceId, null, failing)

        val result = svc.importTree("content://p/dead-tree", ConflictPolicy.ASK, never) { _, _ -> }

        assertEquals(1, result.items.size)
        assertEquals(TransferItemStatus.FAILED, result.items.single().status)
    }

    // ── 导出: single document destination ─────────────────────────────────────────────────

    private fun seedExportSource(
        rel: String,
        bytes: ByteArray,
    ) {
        Files.write(scopeRoot.resolve(rel), bytes)
    }

    /** Export with the default no-op progress (keeps the call sites under the line limit). */
    private fun export(
        rel: String,
        target: ExportTarget,
        svc: FileManagerService = service,
        policy: ConflictPolicy = ConflictPolicy.ASK,
    ): TransferResult = svc.exportDocument(rel, target, policy, never) { _, _ -> }

    @Test
    fun exportSingleToADocumentIsVerifiedWhenTheReReadMatches() {
        val payload = "export me\n".toByteArray()
        seedExportSource("input/export.txt", payload)
        reportedDestSize = payload.size.toLong() // the destination honestly reports its size
        reReadBytes = payload // and the re-read seam can read exactly what was written

        val result = export("input/export.txt", ExportTarget.Document("content://p/sink", "sink.txt"))

        val item = result.items.single()
        assertEquals(TransferItemStatus.COMPLETED, item.status)
        assertArrayEquals(payload, sink.bytes())
        assertTrue("an honest destination + matching re-read is verified", item.verified)
        assertTrue(item.sizeVerified)
        assertEquals(hex(payload), item.sha256)
    }

    @Test
    fun exportSingleToALyingDestinationFailsTheSizeReCheck() {
        val payload = "export me\n".toByteArray()
        seedExportSource("input/export.txt", payload)
        reportedDestSize = payload.size + 5L // the destination under-reports its truth

        val result = export("input/export.txt", ExportTarget.Document("content://p/sink", "sink.txt"))

        val item = result.items.single()
        assertEquals(TransferItemStatus.FAILED, item.status)
        assertFalse("a size-mismatching destination is never verified", item.verified)
    }

    @Test
    fun exportSingleToAnUnreReadableDestinationCompletesWithoutVerified() {
        val payload = "export me\n".toByteArray()
        seedExportSource("input/export.txt", payload)
        reportedDestSize = payload.size.toLong()
        reReadBytes = null // the destination cannot be re-read: platform-confirmed only

        val result = export("input/export.txt", ExportTarget.Document("content://p/sink", "sink.txt"))

        val item = result.items.single()
        assertEquals(TransferItemStatus.COMPLETED, item.status)
        assertFalse("no re-read evidence → not verified", item.verified)
        assertTrue("the platform size re-check still passed", item.sizeVerified)
    }

    @Test
    fun exportSingleOutsideTheUserRegionsIsRefused() {
        // `.helix/` internals are never exportable (the HXA-044 region gate, unchanged).
        val result = export(".helix/metadata.json", ExportTarget.Document("content://p/sink", "s"))

        assertEquals(TransferItemStatus.FAILED, result.items.single().status)
        assertEquals(0, sink.size())
    }

    @Test
    fun exportSingleCancelledMidCopyLeavesAPartialDestinationHonest() {
        val payload = ByteArray(1024 * 1024) { (it % 251).toByte() }
        seedExportSource("work/big.bin", payload)
        val cancel = AtomicBoolean(false)

        val result =
            service.exportDocument(
                "work/big.bin",
                ExportTarget.Document("content://p/sink", "big.bin"),
                ConflictPolicy.ASK,
                SafCancelToken { cancel.get() },
            ) { done, _ ->
                if (done >= 128 * 1024) cancel.set(true)
            }

        val item = result.items.single()
        assertEquals(TransferItemStatus.CANCELLED, item.status)
        val partial = sink.size() in 128 * 1024 until payload.size
        assertTrue("the destination holds a PARTIAL document (a content URI cannot be truncated)", partial)
    }

    @Test
    fun exportSingleToAnUnopenableDestinationFailsClosed() {
        val payload = "x".toByteArray()
        seedExportSource("input/x.txt", payload)
        val failingAccess =
            rebuilt(
                exportPipeline =
                    SafExportPipeline(
                        ScopeRootResolver { _ -> scopeRoot },
                        SafDestinationOpener { throw SecurityException("destination revoked") },
                        SafDestinationVerifier { -1L },
                    ),
            )
        val svc = FileManagerService(store, ScopeRootResolver { _ -> scopeRoot }, workspaceId, null, failingAccess)

        val result = export("input/x.txt", ExportTarget.Document("content://p/dead", "x"), svc)

        assertEquals(TransferItemStatus.FAILED, result.items.single().status)
    }

    // ── 导出: into an authorized SAF tree (HXA-057 grant, WRITE re-verified) ──────────────

    private lateinit var treeUri: String
    private lateinit var treeScope: String

    private fun makeSaf(
        writable: Boolean,
        live: Boolean,
        readerNames: Set<String> = emptySet(),
    ): FileManagerService {
        val grantStore = SafGrantStore(tmp.newFolder("saf").toPath().resolve("g.json")) { 0L }
        treeUri = "content://host/tree/docs"
        grantStore.grant(treeUri, "Docs")
        treeScope = grantStore.deriveScopeId(treeUri)
        val check =
            SafTreeGrantCheck { uri ->
                if (live && uri == treeUri) {
                    SafTreeGrantFacts(
                        rootLive = true,
                        authority = "host",
                        rootDocumentId = "docs",
                        readable = true,
                        writable = writable,
                    )
                } else {
                    null
                }
            }
        val service2 = SafTreeScopeService(grantStore, check)
        val access2 =
            SafTreeScopeAccess(
                service = service2,
                reader = FakeTreeReader(readerNames.map { SafTreeFileEntry(it, false, -1L, 123L) }),
                shareDir = tmp.newFolder("share").toPath(),
            )
        return FileManagerService(store, ScopeRootResolver { _ -> scopeRoot }, workspaceId, access2, access)
    }

    @Test
    fun exportToATreeCreatesTheDocumentAndVerifies() {
        val payload = "into the tree\n".toByteArray()
        seedExportSource("input/tree.txt", payload)
        reportedDestSize = payload.size.toLong()
        reReadBytes = payload
        val svc = makeSaf(writable = true, live = true)

        val result = export("input/tree.txt", ExportTarget.TreeDestination(treeScope, ""), svc)

        val item = result.items.single()
        assertEquals(TransferItemStatus.COMPLETED, item.status)
        assertEquals("tree.txt", treeDest.requested.single())
        assertTrue(item.verified)
        assertArrayEquals(payload, sink.bytes())
    }

    @Test
    fun exportToAReadOnlyTreeGrantFailsClosedBeforeAnyByte() {
        val payload = "x".toByteArray()
        seedExportSource("input/tree.txt", payload)
        val svc = makeSaf(writable = false, live = true)

        val result = export("input/tree.txt", ExportTarget.TreeDestination(treeScope, ""), svc)

        val item = result.items.single()
        assertEquals(TransferItemStatus.FAILED, item.status)
        assertTrue("a read-only grant writes nothing", sink.size() == 0)
    }

    @Test
    fun exportToARevokedTreeGrantFailsClosed() {
        seedExportSource("input/tree.txt", "x".toByteArray())
        val svc = makeSaf(writable = true, live = false)

        val result = export("input/tree.txt", ExportTarget.TreeDestination(treeScope, ""), svc)

        assertEquals(TransferItemStatus.FAILED, result.items.single().status)
        assertEquals(0, sink.size())
    }

    @Test
    fun exportToATreeWithANameConflictUnderAskReportsConflictWithoutWriting() {
        seedExportSource("input/tree.txt", "x".toByteArray())
        treeDest.existing += "tree.txt"
        val svc = makeSaf(writable = true, live = true)

        val result = export("input/tree.txt", ExportTarget.TreeDestination(treeScope, ""), svc)

        assertEquals(TransferItemStatus.CONFLICT, result.items.single().status)
        assertEquals(0, sink.size())
    }

    @Test
    fun exportToATreeWithANameConflictUnderRenameUsesTheSuffixedName() {
        seedExportSource("input/tree.txt", "x".toByteArray())
        treeDest.existing += "tree.txt"
        val svc = makeSaf(writable = true, live = true, readerNames = setOf("tree.txt"))

        val result = export("input/tree.txt", ExportTarget.TreeDestination(treeScope, ""), svc, ConflictPolicy.RENAME)

        val item = result.items.single()
        assertEquals(TransferItemStatus.COMPLETED, item.status)
        assertEquals(listOf("tree.txt", "tree (1).txt"), treeDest.requested)
        assertTrue(item.targetLabel.contains("tree (1).txt"))
    }

    @Test
    fun exportToATreeWithANameConflictUnderOverwriteReusesTheExistingDocument() {
        seedExportSource("input/tree.txt", "y".toByteArray())
        treeDest.existing += "tree.txt"
        val svc = makeSaf(writable = true, live = true)

        val treeTarget = ExportTarget.TreeDestination(treeScope, "")
        val result = export("input/tree.txt", treeTarget, svc, ConflictPolicy.OVERWRITE)

        val item = result.items.single()
        assertEquals(TransferItemStatus.COMPLETED, item.status)
        assertTrue("the overwrite reused the existing document", treeDest.overwriteRequested)
    }

    @Test
    fun exportToATreeWithAMissingParentFailsClosed() {
        seedExportSource("input/tree.txt", "x".toByteArray())
        treeDest.missingParent = true
        val svc = makeSaf(writable = true, live = true)

        val result = export("input/tree.txt", ExportTarget.TreeDestination(treeScope, "no/such/dir"), svc)

        assertEquals(TransferItemStatus.FAILED, result.items.single().status)
        assertEquals(0, sink.size())
    }

    // ── no-wiring fail-closed ─────────────────────────────────────────────────────────────

    @Test
    fun transferEntriesFailClosedWhenTheAccessIsUnwired() {
        val svc = FileManagerService(store, ScopeRootResolver { _ -> scopeRoot }, workspaceId, null, null)
        seedExportSource("input/x.txt", "x".toByteArray())

        val importResult = svc.importSingleDocument("content://p/doc", ConflictPolicy.ASK, never) { _, _ -> }
        assertEquals(TransferItemStatus.FAILED, importResult.items.single().status)
        val exportResult = export("input/x.txt", ExportTarget.Document("content://p/sink", "x"), svc)
        assertEquals(TransferItemStatus.FAILED, exportResult.items.single().status)
    }

    // ── fakes ─────────────────────────────────────────────────────────────────────────────

    /** Rebuilds the access bundle with a swapped seam (the class is not a data class). */
    private fun rebuilt(
        importPipeline: SafImportPipeline = access.importPipeline,
        exportPipeline: SafExportPipeline = access.exportPipeline,
        treeLister: SafTreeLister = access.treeLister,
    ) = SafImportExportAccess(
        importPipeline = importPipeline,
        exportPipeline = exportPipeline,
        sourceMetadata = access.sourceMetadata,
        treeLister = treeLister,
        treeDestination = access.treeDestination,
        destinationReReader = access.destinationReReader,
    )

    /** An OutputStream that records what the destination seam writes (re-readable afterwards). */
    private class ByteSink : java.io.OutputStream() {
        private val out = ByteArrayOutputStream()

        override fun write(b: Int) {
            out.write(b)
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            out.write(b, off, len)
        }

        fun bytes(): ByteArray = out.toByteArray()

        fun size(): Int = out.size()
    }

    /** A configurable tree destination seam. */
    private class FakeTreeDestination : SafTreeDestination {
        val existing = mutableSetOf<String>()
        val requested = mutableListOf<String>()
        var overwriteRequested = false
        var missingParent = false

        override fun destinationUri(
            scopeId: String,
            parentPath: String,
            displayName: String,
            mimeType: String,
            overwrite: Boolean,
        ): String {
            requested += displayName
            if (missingParent) throw java.io.FileNotFoundException("parent not found")
            if (displayName in existing) {
                if (overwrite) {
                    overwriteRequested = true
                    return "content://p/tree/$displayName"
                }
                throw FileAlreadyExistsException(displayName, null, "exists")
            }
            existing += displayName
            return "content://p/tree/$displayName"
        }
    }

    /** A minimal [SafTreeReader] stub: only [list] is used by the transfer seam. */
    private class FakeTreeReader(
        private val entries: List<SafTreeFileEntry>,
    ) : SafTreeReader {
        override fun list(
            scopeId: String,
            relativePath: String,
        ): List<SafTreeFileEntry> = entries

        override fun read(
            scopeId: String,
            relativePath: String,
            offset: Long,
            maxBytes: Long,
        ): ByteArray = throw UnsupportedOperationException("not used")

        override fun stat(
            scopeId: String,
            relativePath: String,
        ): SafTreeStat = throw UnsupportedOperationException("not used")

        override fun copyToAppPrivate(
            scopeId: String,
            relativePath: String,
            target: Path,
            maxBytes: Long,
        ): Long = throw UnsupportedOperationException("not used")
    }
}
