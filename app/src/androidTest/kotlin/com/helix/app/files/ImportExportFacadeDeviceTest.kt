package com.helix.app.files

import android.content.Context
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.test.TransferTestDocumentsProvider
import com.helix.core.workspace.AtomicFileWriter
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.feature.files.ContentResolverSafDestinationOpener
import com.helix.feature.files.ContentResolverSafDestinationReReader
import com.helix.feature.files.ContentResolverSafDestinationVerifier
import com.helix.feature.files.ContentResolverSafMetadataReader
import com.helix.feature.files.ContentResolverSafSourceOpener
import com.helix.feature.files.ContentResolverSafTreeCheck
import com.helix.feature.files.ContentResolverSafTreeDestination
import com.helix.feature.files.ContentResolverSafTreeLister
import com.helix.feature.files.ContentResolverSafTreeReader
import com.helix.feature.files.SafCancelToken
import com.helix.feature.files.SafExportPipeline
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafImportExportAccess
import com.helix.feature.files.SafImportPipeline
import com.helix.feature.files.SafTreeGrantCheck
import com.helix.feature.files.SafTreeGrantFacts
import com.helix.feature.files.SafTreeScopeAccess
import com.helix.feature.files.SafTreeScopeService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HXA-058 (device gate): the file manager's 导入/导出 entries end to end — the REAL HXA-044
 * pipelines + the REAL `ContentResolver` adapters (source metadata / opener, destination opener /
 * size re-check / re-read, tree lister / tree destination, the HXA-057 grant re-verification)
 * against the in-APK [TransferTestDocumentsProvider] fixtures:
 *
 * - 导入 (single document): honest publish, a LYING reported size (fail closed, nothing
 *   published), a revoked/denied source (fail closed);
 * - 导入 (folder): the real tree enumeration + the planner's ambiguous-name skips;
 * - 导出 (document): the honest sink (COMPLETED + size re-check + byte RE-READ verified), the
 *   lying sink (the post-write size re-check refusal), a mid-copy cancel;
 * - 导出 (into an authorized tree): the real HXA-057 grant + WRITE re-verification (a grant
 *   without a persisted WRITE permission fails closed — the real check, no seam), the
 *   create-document round-trip + verified re-read and the same-name conflict (ASK reports,
 *   never clobbers) — the WRITE-branch of those two runs through [WritableOverrideCheck]
 *   (API 30+ cannot mint a persisted WRITE for an in-process fixture provider; every other
 *   re-verification fact stays real);
 * - 进程回收: a crash-leftover temp file is reclaimed by the next transfer.
 *
 * The OS picker round-trip is not driven on device (project precedent); the facade is driven with
 * the picker's RESULT (the document / tree URI), which is the same data the launcher callback
 * hands the UI. A `content://` URI never reaches a model-visible surface (doc 10).
 */
class ImportExportFacadeDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver

    private val scopeId = "hxa058"
    private lateinit var wsRoot: File
    private lateinit var store: WorkspaceArtifactStore
    private lateinit var grantStore: SafGrantStore
    private lateinit var service: FileManagerService

    private val never = SafCancelToken { false }

    @Before
    fun setUp() {
        wsRoot =
            File(context.filesDir, "hxa058-fixture-ws").apply {
                deleteRecursively()
                mkdirs()
            }
        store = WorkspaceArtifactStore(ScopeRootResolver { _ -> wsRoot.toPath() })
        store.ensureLayout(scopeId)

        grantStore =
            SafGrantStore(File(context.filesDir, "hxa058-fixture-grants").toPath().resolve("g.json")) { 0L }
        service = buildService(writableTree = false)
    }

    /**
     * The same REAL wiring for every test; [writableTree] only swaps the grant re-verification's
     * write bit (see [WritableOverrideCheck]) — API 30+ refuses to mint a persisted URI WRITE
     * grant for an in-process fixture provider (`takePersistableUriPermission` needs a prior
     * temporary grant), so the WRITE branch is exercised with every other fact kept real. The
     * write gate itself stays device-verified fail-closed by
     * [exportToATreeWithoutAWritePermissionFailsClosed] (the REAL check, no override).
     */
    private fun buildService(writableTree: Boolean): FileManagerService {
        val treeService =
            SafTreeScopeService(
                grantStore,
                if (writableTree) {
                    WritableOverrideCheck(ContentResolverSafTreeCheck(resolver))
                } else {
                    ContentResolverSafTreeCheck(resolver)
                },
            )
        val treeAccess =
            SafTreeScopeAccess(
                treeService,
                ContentResolverSafTreeReader(resolver, grantStore),
                File(context.filesDir, "hxa058-share").toPath(),
            )
        val transferAccess =
            SafImportExportAccess(
                importPipeline =
                    SafImportPipeline(
                        ScopeRootResolver { _ ->
                            wsRoot.toPath()
                        },
                        ContentResolverSafSourceOpener(resolver),
                    ),
                exportPipeline =
                    SafExportPipeline(
                        ScopeRootResolver { _ -> wsRoot.toPath() },
                        ContentResolverSafDestinationOpener(resolver),
                        ContentResolverSafDestinationVerifier(resolver),
                    ),
                sourceMetadata = ContentResolverSafMetadataReader(resolver),
                treeLister = ContentResolverSafTreeLister(resolver),
                treeDestination = ContentResolverSafTreeDestination(resolver, grantStore),
                destinationReReader = ContentResolverSafDestinationReReader(resolver),
            )
        return FileManagerService(
            store,
            ScopeRootResolver { _ ->
                wsRoot.toPath()
            },
            scopeId,
            treeAccess,
            transferAccess,
        )
    }

    /**
     * The WRITE branch of the re-verification with every other fact REAL: liveness, authority,
     * root document id and readability come from the real [ContentResolverSafTreeCheck]; only
     * `writable` is forced (the platform cannot mint a persisted WRITE for an in-process fixture
     * provider on API 30+). The fail-closed side of the gate is covered without this seam.
     */
    private class WritableOverrideCheck(
        private val real: SafTreeGrantCheck,
    ) : SafTreeGrantCheck {
        override fun verify(treeUri: String): SafTreeGrantFacts? = real.verify(treeUri)?.copy(writable = true)
    }

    private fun docUri(id: String): String = "content://${TransferTestDocumentsProvider.AUTHORITY}/document/$id"

    private fun seed(
        relative: String,
        content: String,
    ) {
        val f = File(wsRoot, relative)
        f.parentFile?.mkdirs()
        f.writeText(content)
    }

    // ── 导入: 单个文档 ────────────────────────────────────────────────────────────────────

    @Test
    fun importSingleHonestPublishesIntoWorkspaceInput() {
        val result = service.importSingleDocument(docUri("honest"), ConflictPolicy.ASK, never) { _, _ -> }

        val item = result.items.single()
        assertEquals(TransferItemStatus.COMPLETED, item.status)
        assertEquals("input/honest.txt", item.targetLabel)
        assertEquals(6L, item.sizeBytes)
        assertEquals("hello\n", File(wsRoot, "input/honest.txt").readText())
        assertEquals(0, AtomicFileWriter.cleanup(wsRoot.toPath()))
    }

    // A source that REPORTS 4 bytes but streams 10: the pipeline refuses, publishes NOTHING,
    // and leaves no temp file (the lying-provider contract, unchanged from HXA-044).
    @Test
    fun importSingleUnderReportedSizeFailsClosedPublishingNothing() {
        val result = service.importSingleDocument(docUri("under"), ConflictPolicy.ASK, never) { _, _ -> }

        val item = result.items.single()
        assertEquals(TransferItemStatus.FAILED, item.status)
        assertFalse("nothing is published for a size the stream contradicts", File(wsRoot, "input/under.txt").exists())
        assertEquals(0, AtomicFileWriter.cleanup(wsRoot.toPath()))
    }

    // A source whose open is refused (a revoked grant shape): fail closed, nothing published.
    @Test
    fun importSingleDeniedSourceFailsClosed() {
        val result = service.importSingleDocument(docUri("denied"), ConflictPolicy.ASK, never) { _, _ -> }
        assertEquals(TransferItemStatus.FAILED, result.items.single().status)
        assertFalse(File(wsRoot, "input/denied.txt").exists())
    }

    // 进程回收: a temp file a crashed transfer left behind is reclaimed by the next operation.
    @Test
    fun aCrashedLeftoverTempIsReclaimedByTheNextTransfer() {
        File(wsRoot, "input/.helix-tmp-crashed").writeText("stale")
        val result = service.importSingleDocument(docUri("honest"), ConflictPolicy.ASK, never) { _, _ -> }
        assertEquals(TransferItemStatus.COMPLETED, result.items.single().status)
        assertEquals(1, result.reclaimedTempFiles)
        assertFalse(File(wsRoot, "input/.helix-tmp-crashed").exists())
    }

    // ── 导入: 文件夹 ──────────────────────────────────────────────────────────────────────

    @Test
    fun importTreeCopiesFilesAndSkipsAmbiguousNames() {
        val result = service.importTree(TransferTestDocumentsProvider.TREE_URI, ConflictPolicy.ASK, never) { _, _ -> }

        val bySource = result.items.associateBy { it.sourceLabel }
        assertEquals(TransferItemStatus.COMPLETED, bySource["note.txt"]!!.status)
        assertEquals("v1", File(wsRoot, "input/note.txt").readText())
        val dups = result.items.filter { it.sourceLabel == "tdup/same.txt" }
        assertEquals("both ambiguous entries are reported (never a silent omission or a guess)", 2, dups.size)
        assertTrue(dups.all { it.status == TransferItemStatus.SKIPPED })
        assertFalse(File(wsRoot, "input/tdup").exists())
    }

    // ── 导出: 单个文档目标 ────────────────────────────────────────────────────────────────

    @Test
    fun exportToAHonestSinkCompletesVerified() {
        seed("input/export.txt", "export me\n")
        val result =
            service.exportDocument(
                "input/export.txt",
                ExportTarget.Document(docUri("sink"), "sink.txt"),
                ConflictPolicy.ASK,
                never,
            ) { _, _ -> }

        val item = result.items.single()
        assertEquals(TransferItemStatus.COMPLETED, item.status)
        assertTrue("the platform size re-check passed", item.sizeVerified)
        assertTrue("the bytes were re-read after the write and are hash-equal", item.verified)
        assertTrue("the result says the verification matched", (item.detail ?: "").contains("校验一致"))
    }

    // A destination that REPORTS size+3 after the write: the post-write size re-check refuses —
    // never verified, the export is reported as a failure (HXA-044's target-side defense).
    @Test
    fun exportToALyingSinkFailsTheSizeReCheck() {
        seed("input/export.txt", "export me\n")
        val result =
            service.exportDocument(
                "input/export.txt",
                ExportTarget.Document(docUri("liar"), "liar.txt"),
                ConflictPolicy.ASK,
                never,
            ) { _, _ -> }

        val item = result.items.single()
        assertEquals(TransferItemStatus.FAILED, item.status)
        assertFalse(item.verified)
        assertFalse(item.sizeVerified)
    }

    // A cancel mid-copy: the destination may keep a PARTIAL document (a content URI cannot be
    // truncated) — the result says CANCELLED, never COMPLETED.
    @Test
    fun exportCancelledMidCopyIsReportedCancelled() {
        val payload = ByteArray(1024 * 1024) { (it % 251).toByte() }
        val f = File(wsRoot, "work/big.bin")
        f.parentFile?.mkdirs()
        f.writeBytes(payload)
        val cancel = AtomicBoolean(false)

        val result =
            service.exportDocument(
                "work/big.bin",
                ExportTarget.Document(docUri("sink"), "big.bin"),
                ConflictPolicy.ASK,
                SafCancelToken { cancel.get() },
            ) { done, _ ->
                if (done >= 128 * 1024) cancel.set(true)
            }

        assertEquals(TransferItemStatus.CANCELLED, result.items.single().status)
    }

    // The region gate (HXA-044, unchanged): `.helix/` internals are never exportable.
    @Test
    fun exportOutsideTheUserRegionsIsRefused() {
        val result =
            service.exportDocument(
                ".helix/metadata.json",
                ExportTarget.Document(docUri("sink"), "s"),
                ConflictPolicy.ASK,
                never,
            ) { _, _ -> }
        assertEquals(TransferItemStatus.FAILED, result.items.single().status)
    }

    // ── 导出: 到已授权 tree ───────────────────────────────────────────────────────────────

    @Test
    fun exportToATreeUnderAWriteGrantCreatesAndVerifies() {
        service = buildService(writableTree = true)
        val scope = grantStore.grant(TransferTestDocumentsProvider.TREE_URI, "Transfer Tree").scopeId
        seed("input/fresh.txt", "fresh export")

        val result =
            service.exportDocument(
                "input/fresh.txt",
                ExportTarget.TreeDestination(scope, ""),
                ConflictPolicy.ASK,
                never,
            ) { _, _ -> }

        val item = result.items.single()
        assertEquals(TransferItemStatus.COMPLETED, item.status)
        assertTrue("the create-document round-trip + byte re-read verify the export", item.verified)
        assertTrue("the platform size re-check passed", item.sizeVerified)
    }

    // A same-name file in the tree under ASK: CONFLICT (surfaced), and the existing document is
    // NEVER clobbered (a default overwrite is impossible here).
    @Test
    fun exportToATreeWithASameNameUnderAskReportsConflict() {
        service = buildService(writableTree = true)
        val scope = grantStore.grant(TransferTestDocumentsProvider.TREE_URI, "Transfer Tree").scopeId
        seed("input/note.txt", "would clobber")

        val result =
            service.exportDocument(
                "input/note.txt",
                ExportTarget.TreeDestination(scope, ""),
                ConflictPolicy.ASK,
                never,
            ) { _, _ -> }

        assertEquals(TransferItemStatus.CONFLICT, result.items.single().status)
        // The tree's note.txt still holds its original content.
        val resolver2 = context.contentResolver
        resolver2
            .openInputStream(
                Uri.parse("content://${TransferTestDocumentsProvider.AUTHORITY}/document/tnote"),
            )!!
            .use {
                assertEquals("v1", String(it.readBytes()))
            }
    }

    // A grant WITHOUT a persisted WRITE permission (read-only) fails closed before any byte is
    // written (HXA-057's re-verification, unchanged).
    @Test
    fun exportToATreeWithoutAWritePermissionFailsClosed() {
        val scope = grantStore.grant(TransferTestDocumentsProvider.TREE_URI, "Read Only Tree").scopeId
        seed("input/readonly.txt", "x")

        val result =
            service.exportDocument(
                "input/readonly.txt",
                ExportTarget.TreeDestination(scope, ""),
                ConflictPolicy.ASK,
                never,
            ) { _, _ -> }

        val item = result.items.single()
        assertEquals(TransferItemStatus.FAILED, item.status)
        assertTrue((item.detail ?: "").contains("写权限"))
    }
}
