package com.helix.feature.files

import com.helix.core.workspace.AtomicFileWriter
import com.helix.core.workspace.ScopeNotAvailable
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * HXA-044: [SafImportPipeline] — importing a SAF document into `input/`, fail-closed against a
 * LYING provider (doc 07: 谎报 size/MIME/display name).
 *
 * Axes covered:
 * - a normal import publishes atomically, hashes the real bytes, re-detects the MIME (the
 *   reported MIME is never trusted) and exposes only the model-safe reference;
 * - a provider that under- or over-reports its size is refused at EOF and the published file
 *   is deleted (nothing the user was not told about survives);
 * - a provider with UNKNOWN size that exceeds the hard cap is aborted mid-stream (temp deleted);
 * - an evil display name is sanitized; an existing destination is refused; the reported size is
 *   admitted against the import limit and the quota headroom before a byte is read;
 * - cancellation (before start and mid-stream) publishes nothing and leaves no temp;
 * - the optional artifact sink runs only after durability and a failing sink is not fatal.
 */
class SafImportPipelineTest {
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

    private fun pipeline(
        root: Path,
        bytes: ByteArray,
        limits: SafImportLimits = SafImportLimits(),
    ): SafImportPipeline = SafImportPipeline(resolver(root), SafSourceOpener { ByteArrayInputStream(bytes) }, limits)

    private fun resolver(root: Path) = ScopeRootResolver { _ -> root }

    private fun honest(bytes: ByteArray) = SafSourceMetadata(bytes.size.toLong(), null, "doc.txt")

    private fun run(
        root: Path,
        bytes: ByteArray,
        reported: SafSourceMetadata? = null,
        limits: SafImportLimits = SafImportLimits(),
        cancel: SafCancelToken = never,
        targetNameOverride: String? = null,
    ): SafImportOutcome =
        pipeline(root, bytes, limits).importDocument(
            "ws",
            "content://p/doc",
            reported ?: honest(bytes),
            targetNameOverride,
            cancel,
        )

    // ── Normal path ─────────────────────────────────────────────────────────────────────

    @Test
    fun aNormalImportPublishesHashesTheRealBytesAndIgnoresTheReportedMime() {
        val root = inputDir()
        val bytes = "hello saf import\n".toByteArray()
        val lying = SafSourceMetadata(bytes.size.toLong(), "image/png", "doc.txt")
        val outcome = run(root, bytes, lying)

        assertEquals(ImportStatus.COMPLETED, outcome.status)
        assertEquals(bytes.size.toLong(), outcome.sizeBytes)
        assertEquals(hex(bytes), outcome.sha256)
        assertEquals("scope:ws:input/doc.txt", outcome.targetModelRef)
        assertEquals("doc.txt", outcome.targetName)
        assertTrue("the real bytes say text; the lying MIME must be ignored", outcome.isText)
        assertTrue(outcome.mimeType != null)
        assertEquals("hello saf import\n", String(Files.readAllBytes(root.resolve("input/doc.txt"))))
        assertEquals("no orphan temp after a clean publish", 0, AtomicFileWriter.cleanup(root))
    }

    @Test
    fun aNameOverrideReplacesTheProviderDisplayName() {
        val root = inputDir()
        val bytes = "x".toByteArray()
        val outcome = run(root, bytes, honest(bytes).copy(displayName = "evil"), targetNameOverride = "renamed.txt")
        assertEquals(ImportStatus.COMPLETED, outcome.status)
        assertEquals("renamed.txt", outcome.targetName)
        assertTrue(Files.isRegularFile(root.resolve("input/renamed.txt")))
    }

    // ── Lying provider ──────────────────────────────────────────────────────────────────

    @Test
    fun anUnderReportingProviderIsRefusedAndThePublishedFileIsDeleted() {
        val root = inputDir()
        val bytes = ByteArray(100) { it.toByte() }
        val lying = SafSourceMetadata(10L, "application/octet-stream", "doc.bin")
        val outcome = run(root, bytes, lying)

        assertEquals(ImportRefusal.STREAM_SIZE_MISMATCH, outcome.refusal)
        assertFalse("the just-published file must be gone", Files.exists(root.resolve("input/doc.bin")))
        assertEquals(0, AtomicFileWriter.cleanup(root))
    }

    @Test
    fun anOverReportingProviderIsRefusedAtEof() {
        val root = inputDir()
        val bytes = "short".toByteArray()
        val lying = SafSourceMetadata(1_000_000L, null, "doc.bin")
        val outcome = run(root, bytes, lying)

        assertEquals(ImportRefusal.STREAM_SIZE_MISMATCH, outcome.refusal)
        assertFalse(Files.exists(root.resolve("input/doc.bin")))
    }

    @Test
    fun anUnknownSizeStreamAboveTheHardCapIsAbortedMidStream() {
        val root = inputDir()
        val bytes = ByteArray(2048)
        val unknown = SafSourceMetadata(-1L, null, "big.bin")
        val outcome = run(root, bytes, unknown, limits = SafImportLimits(maxImportBytes = 1024L))

        assertEquals(ImportRefusal.STREAM_LIMIT_EXCEEDED, outcome.refusal)
        assertFalse(Files.exists(root.resolve("input/big.bin")))
        assertEquals("the aborted temp must be deleted", 0, AtomicFileWriter.cleanup(root))
    }

    @Test
    fun anEvilDisplayNameIsSanitizedIntoALegalSegment() {
        val root = inputDir()
        val bytes = "payload".toByteArray()
        val evil = SafSourceMetadata(bytes.size.toLong(), null, "../../etc/evil\u0000name")
        val outcome = run(root, bytes, evil)

        assertEquals(ImportStatus.COMPLETED, outcome.status)
        assertEquals(".._.._etc_evilname", outcome.targetName)
        assertTrue(Files.isRegularFile(root.resolve("input/.._.._etc_evilname")))
    }

    @Test
    fun aNullDisplayNameFallsBackToTheStableName() {
        val root = inputDir()
        val noName = SafSourceMetadata(4L, null, null)
        val outcome = run(root, "data".toByteArray(), noName)
        assertEquals(ImportStatus.COMPLETED, outcome.status)
        assertEquals(SafNameSanitizer.FALLBACK, outcome.targetName)
    }

    // ── Admission gates (before a byte is read) ─────────────────────────────────────────

    @Test
    fun aReportedSizeAboveTheImportLimitIsRefusedBeforeOpeningTheStream() {
        val root = inputDir()
        val opened = AtomicBoolean(false)
        val opener =
            SafSourceOpener {
                opened.set(true)
                ByteArrayInputStream("tiny".toByteArray())
            }
        val pipeline = SafImportPipeline(resolver(root), opener, SafImportLimits(maxImportBytes = 100L))
        val tooBig = SafSourceMetadata(1_000L, null, "doc.txt")
        val outcome = pipeline.importDocument("ws", "content://p/doc", tooBig, null, never)

        assertEquals(ImportRefusal.REPORTED_SIZE_EXCEEDS_LIMIT, outcome.refusal)
        assertFalse("the stream must never be opened", opened.get())
    }

    @Test
    fun aQuotaWithoutHeadroomForTheReportedSizeIsRefused() {
        val root = inputDir()
        // 190 bytes already used in a 200-byte quota: the reported 16 does not fit the 10 left.
        Files.write(root.resolve("input/existing.bin"), ByteArray(190))
        val outcome = run(root, "a longer payload".toByteArray(), limits = SafImportLimits(quotaMaxBytes = 200L))

        assertEquals(ImportRefusal.QUOTA_EXCEEDED, outcome.refusal)
        assertFalse(Files.exists(root.resolve("input/doc.txt")))
    }

    // ── Cancellation ────────────────────────────────────────────────────────────────────

    @Test
    fun aCancelBeforeStartPublishesNothingAndOpensNothing() {
        val root = inputDir()
        val opened = AtomicBoolean(false)
        val opener =
            SafSourceOpener {
                opened.set(true)
                ByteArrayInputStream("x".toByteArray())
            }
        val pipeline = SafImportPipeline(resolver(root), opener)
        val outcome =
            pipeline.importDocument(
                "ws",
                "content://p/doc",
                honest("x".toByteArray()),
                null,
                SafCancelToken { true },
            )

        assertEquals(ImportStatus.CANCELLED, outcome.status)
        assertFalse(opened.get())
        assertEquals(0, AtomicFileWriter.cleanup(root))
    }

    @Test
    fun aCancelMidStreamPublishesNothingAndDeletesTheTemp() {
        val root = inputDir()
        val reads = AtomicInteger(0)
        val chunk = ByteArray(64 * 1024)
        val endless =
            object : InputStream() {
                override fun read(): Int = -1

                override fun read(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ): Int {
                    reads.incrementAndGet()
                    chunk.copyInto(b, off, 0, len)
                    return len
                }
            }
        val cancel = SafCancelToken { reads.get() >= 3 }
        val pipeline = SafImportPipeline(resolver(root), SafSourceOpener { endless })
        val outcome =
            pipeline.importDocument(
                "ws",
                "content://p/doc",
                SafSourceMetadata(-1L, null, "big.bin"),
                null,
                cancel,
            )

        assertEquals(ImportStatus.CANCELLED, outcome.status)
        assertTrue("the stream must have been read in chunks before cancelling", reads.get() >= 3)
        assertFalse(Files.exists(root.resolve("input/big.bin")))
        assertEquals(0, AtomicFileWriter.cleanup(root))
    }

    // ── Failure paths ───────────────────────────────────────────────────────────────────

    @Test
    fun anUnopenableSourceIsRefused() {
        val root = inputDir()
        val opener = SafSourceOpener { throw SecurityException("access denied") }
        val pipeline = SafImportPipeline(resolver(root), opener)
        val outcome = pipeline.importDocument("ws", "content://p/doc", honest("x".toByteArray()), null, never)
        assertEquals(ImportRefusal.SOURCE_UNOPENABLE, outcome.refusal)
    }

    @Test
    fun aScopeWithoutAUsableRootIsRefusedWithoutRealPaths() {
        val root = inputDir()
        val broken = ScopeRootResolver { throw ScopeNotAvailable("gone") }
        val pipeline = SafImportPipeline(broken, SafSourceOpener { ByteArrayInputStream("x".toByteArray()) })
        val outcome = pipeline.importDocument("ws", "content://p/doc", honest("x".toByteArray()), null, never)

        assertEquals(ImportRefusal.SCOPE_UNAVAILABLE, outcome.refusal)
        val detail = outcome.detail
        assertNull(
            "a real path must never leak into the detail",
            detail?.takeIf { it.contains(root.toString()) },
        )
    }

    @Test
    fun anExistingDestinationIsRefused() {
        val root = inputDir()
        Files.write(root.resolve("input/doc.txt"), "old".toByteArray())
        val outcome = run(root, "new".toByteArray())
        assertEquals(ImportRefusal.DESTINATION_EXISTS, outcome.refusal)
        val kept = String(Files.readAllBytes(root.resolve("input/doc.txt")))
        assertEquals("the previous file must be untouched", "old", kept)
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
    fun aSinkIsRegisteredOnlyAfterTheFileIsDurable() {
        val root = inputDir()
        val bytes = "artifact payload".toByteArray()
        val sink = RecordingSink()
        val pipeline = pipeline(root, bytes)
        val outcome =
            pipeline.importDocument(
                "ws",
                "content://p/doc",
                honest(bytes),
                null,
                never,
                sink = sink,
                sessionId = "sess_1",
            )

        assertEquals(ImportStatus.COMPLETED, outcome.status)
        assertTrue(outcome.artifactRegistered)
        val (sessionId, record) = sink.records.single()
        assertEquals("sess_1", sessionId)
        assertEquals("input/doc.txt", record.relativePath)
        assertEquals(hex(bytes), record.sha256)
        assertEquals(bytes.size.toLong(), record.sizeBytes)
    }

    @Test
    fun aFailingSinkIsReportedButNotFatal() {
        val root = inputDir()
        val bytes = "payload".toByteArray()
        val failing = WorkspaceArtifactStore.ArtifactSink { _, _ -> throw IllegalStateException("sink down") }
        val outcome =
            pipeline(root, bytes).importDocument(
                "ws",
                "content://p/doc",
                honest(bytes),
                null,
                never,
                sink = failing,
                sessionId = "sess_1",
            )

        assertEquals(ImportStatus.COMPLETED, outcome.status)
        assertFalse(outcome.artifactRegistered)
        assertTrue(Files.isRegularFile(root.resolve("input/doc.txt")))
    }
}
