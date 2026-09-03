package com.helix.feature.files

import com.helix.core.model.AttachmentCategory
import com.helix.core.model.AttachmentClassification
import com.helix.core.model.TextAttachmentKind
import com.helix.core.workspace.AtomicFileWriter
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * HXA-049: [AttachmentImporter] — importing a SAF document as a chat attachment, end to end
 * through [SafImportPipeline]. Covers the per-attachment private path, the 10 MiB attachment cap,
 * the closed classification of the durable bytes, artifact registration, and the no-residue
 * guarantee (a refused or cancelled import leaves no `input/attachments/` entry behind).
 */
class AttachmentImporterTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val never = SafCancelToken { false }

    private fun hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A scope root with the `input/` region pre-created (the layout the import writes into). */
    private fun inputDir(): Path {
        val root = tmp.newFolder("scope").toPath()
        Files.createDirectories(root.resolve("input"))
        return root
    }

    private fun resolver(root: Path) = ScopeRootResolver { _ -> root }

    private fun importer(
        root: Path,
        bytes: ByteArray,
    ): AttachmentImporter =
        AttachmentImporter(SafImportPipeline(resolver(root), SafSourceOpener { ByteArrayInputStream(bytes) }))

    private fun attach(
        root: Path,
        bytes: ByteArray,
        reported: SafSourceMetadata,
        cancel: SafCancelToken = never,
        sink: WorkspaceArtifactStore.ArtifactSink? = null,
        sessionId: String? = null,
    ): AttachmentImportResult =
        importer(root, bytes).importAttachment("ws", "content://p/doc", reported, cancel, sink, sessionId)

    // ── Materializable UTF-8 text ───────────────────────────────────────────────────────

    @Test
    fun aUtf8TextFileBecomesAMaterializableTextAttachment() {
        val root = inputDir()
        val bytes = "hello attachment\n".toByteArray()
        val result = attach(root, bytes, SafSourceMetadata(bytes.size.toLong(), null, "notes.txt"))

        assertEquals(ImportStatus.COMPLETED, result.status)
        assertEquals(AttachmentClassification.TextAttachment(TextAttachmentKind.TXT), result.classification)
        val id = result.attachmentId
        assertNotNull("every completed import has an attachment id", id)
        assertTrue("the id is a stable att_ handle", id!!.startsWith("att_"))
        val name = result.fileName!!
        assertEquals("scope:ws:input/attachments/$id/$name", result.modelRef)
        assertEquals(hex(bytes), result.sha256)
        assertEquals(bytes.size.toLong(), result.sizeBytes)
        assertTrue(Files.isRegularFile(root.resolve("input/attachments/$id/$name")))
        assertEquals("no orphan temp after a clean publish", 0, AtomicFileWriter.cleanup(root))
    }

    @Test
    fun eachFirstBatchKindIsMaterializedFromItsExtension() {
        val root = inputDir()

        fun kind(name: String): AttachmentClassification =
            attach(root, "x".toByteArray(), SafSourceMetadata(1L, null, name)).classification!!
        assertEquals(AttachmentClassification.TextAttachment(TextAttachmentKind.CSV), kind("data.csv"))
        assertEquals(AttachmentClassification.TextAttachment(TextAttachmentKind.JSON), kind("cfg.json"))
        assertEquals(AttachmentClassification.TextAttachment(TextAttachmentKind.MARKDOWN), kind("readme.md"))
    }

    // ── Unsupported categories (closed set) ─────────────────────────────────────────────

    @Test
    fun aUtf16FileIsUnsupportedEvenWithATextName() {
        val root = inputDir()
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hello".toByteArray(Charsets.UTF_16LE)
        val result = attach(root, bytes, SafSourceMetadata(bytes.size.toLong(), null, "notes.txt"))

        assertEquals(ImportStatus.COMPLETED, result.status)
        assertEquals(
            "the byte-derived UTF-16 encoding wins over the .txt name",
            AttachmentClassification.UnsupportedAttachment(AttachmentCategory.TEXT_ENCODING),
            result.classification,
        )
    }

    @Test
    fun aBinaryImageIsAnImageAttachmentWithTheMagicDerivedType() {
        val root = inputDir()
        // HXA-055: PNG magic → the probe detects image/png; the classification is the
        // ImageAttachment branch with the BYTE-derived media type (the untrusted name
        // claiming another type cannot change it).
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val result = attach(root, png, SafSourceMetadata(png.size.toLong(), null, "photo.png"))

        assertEquals(ImportStatus.COMPLETED, result.status)
        assertEquals(
            AttachmentClassification.ImageAttachment("image/png"),
            result.classification,
        )
    }

    // ── No-residue: refused / cancelled imports leave nothing behind ────────────────────

    @Test
    fun anOverCapFileIsRefusedBeforeCopyingAndLeavesNoAttachmentDir() {
        val root = inputDir()
        // The 10 MiB attachment cap: a reported size just above it is refused at admission, before
        // the stream opens and before any attachment dir is created.
        val overCap = AttachmentClassifier.MAX_ATTACHMENT_BYTES + 1
        val result = attach(root, "x".toByteArray(), SafSourceMetadata(overCap, null, "big.bin"))

        assertEquals(ImportStatus.REFUSED, result.status)
        assertEquals(ImportRefusal.REPORTED_SIZE_EXCEEDS_LIMIT, result.refusal)
        assertFalse("a refused import must create no attachment dir", Files.exists(root.resolve("input/attachments")))
    }

    @Test
    fun aCancelledImportIsCancelledAndLeavesNoAttachmentDir() {
        val root = inputDir()
        val result =
            attach(
                root,
                "x".toByteArray(),
                SafSourceMetadata(1L, null, "notes.txt"),
                cancel = SafCancelToken { true },
            )

        assertEquals(ImportStatus.CANCELLED, result.status)
        assertFalse(Files.exists(root.resolve("input/attachments")))
    }

    // ── Artifact registration ───────────────────────────────────────────────────────────

    private class RecordingSink : WorkspaceArtifactStore.ArtifactSink {
        val records = mutableListOf<Pair<String, WorkspaceArtifactStore.ArtifactRecord>>()

        override fun register(
            sessionId: String,
            record: WorkspaceArtifactStore.ArtifactRecord,
        ) {
            records.add(sessionId to record)
        }
    }

    @Test
    fun aCompletedImportRegistersAnArtifactUnderTheAttachmentPath() {
        val root = inputDir()
        val bytes = "artifact attachment".toByteArray()
        val sink = RecordingSink()
        val result =
            attach(
                root,
                bytes,
                SafSourceMetadata(bytes.size.toLong(), null, "notes.txt"),
                sink = sink,
                sessionId = "sess_1",
            )

        assertEquals(ImportStatus.COMPLETED, result.status)
        assertTrue(result.artifactId!!.startsWith("art_"))
        val (sessionId, record) = sink.records.single()
        assertEquals("sess_1", sessionId)
        assertEquals("input/attachments/${result.attachmentId}/${result.fileName}", record.relativePath)
        assertEquals(hex(bytes), record.sha256)
        assertEquals(bytes.size.toLong(), record.sizeBytes)
    }
}
