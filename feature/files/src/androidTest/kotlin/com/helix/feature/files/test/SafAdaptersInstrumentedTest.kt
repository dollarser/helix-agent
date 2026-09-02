package com.helix.feature.files.test

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.workspace.AtomicFileWriter
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.feature.files.ContentResolverSafDestinationOpener
import com.helix.feature.files.ContentResolverSafDestinationVerifier
import com.helix.feature.files.ContentResolverSafGrantProbe
import com.helix.feature.files.ContentResolverSafMetadataReader
import com.helix.feature.files.ContentResolverSafSourceOpener
import com.helix.feature.files.ExportStatus
import com.helix.feature.files.ImportRefusal
import com.helix.feature.files.ImportStatus
import com.helix.feature.files.SafCancelToken
import com.helix.feature.files.SafExportPipeline
import com.helix.feature.files.SafGrantStore
import com.helix.feature.files.SafImportLimits
import com.helix.feature.files.SafImportPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * HXA-044 (device gate): the REAL `ContentResolverSaf*` adapters against the in-APK
 * [LyingContentProvider] (恶意 ContentProvider metadata) and 大流取消 on a stream that never
 * ends. Everything runs through `ContentResolver` — the same code path a hostile real-world
 * provider would take (doc 07).
 */
class SafAdaptersInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver = context.contentResolver

    private val never = SafCancelToken { false }

    private fun uri(case: String) = "content://${LyingContentProvider.AUTHORITY}/$case"

    private fun hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A fresh scope root with an `input/` region; unique per test. */
    private fun scopeRoot(tag: String): Path {
        val root = context.filesDir.toPath().resolve("saf-it-$tag")
        Files.createDirectories(root.resolve("input"))
        return root
    }

    private fun importPipeline(
        root: Path,
        limits: SafImportLimits = SafImportLimits(),
    ): SafImportPipeline =
        SafImportPipeline(ScopeRootResolver { _ -> root }, ContentResolverSafSourceOpener(resolver), limits)

    // ── 恶意 ContentProvider metadata (doc 07) ──────────────────────────────────────────

    @Test
    fun anHonestProviderImportsThroughTheRealAdapters() {
        val root = scopeRoot("honest")
        val bytes = "honest document body\n".toByteArray()
        val metadata = ContentResolverSafMetadataReader(resolver).metadata(uri("honest"))

        assertEquals("the honest provider must report the true size", bytes.size.toLong(), metadata.sizeBytes)
        assertEquals("honest.txt", metadata.displayName)

        val outcome = importPipeline(root).importDocument("ws", uri("honest"), metadata, null, never)

        assertEquals(ImportStatus.COMPLETED, outcome.status)
        assertEquals(hex(bytes), outcome.sha256)
        assertTrue(outcome.isText)
        assertEquals("honest document body\n", String(Files.readAllBytes(root.resolve("input/honest.txt"))))
    }

    @Test
    fun anUnderReportingProviderIsRefusedAndThePublishedFileIsDeleted() {
        val root = scopeRoot("under")
        val metadata = ContentResolverSafMetadataReader(resolver).metadata(uri("under-reported"))

        assertEquals("the provider lies: it reports 10", 10L, metadata.sizeBytes)
        val outcome = importPipeline(root).importDocument("ws", uri("under-reported"), metadata, null, never)

        assertEquals(ImportRefusal.STREAM_SIZE_MISMATCH, outcome.refusal)
        assertFalse(Files.exists(root.resolve("input/under-reported")))
        assertEquals(0, AtomicFileWriter.cleanup(root))
    }

    @Test
    fun anUnknownSizeStreamAboveTheCapIsAbortedMidStream() {
        val root = scopeRoot("cap")
        val metadata = ContentResolverSafMetadataReader(resolver).metadata(uri("unknown-oversized"))

        assertEquals("the provider reports no size", -1L, metadata.sizeBytes)
        val outcome =
            importPipeline(root, SafImportLimits(maxImportBytes = 1024L))
                .importDocument("ws", uri("unknown-oversized"), metadata, null, never)

        assertEquals(ImportRefusal.STREAM_LIMIT_EXCEEDED, outcome.refusal)
        assertFalse(Files.exists(root.resolve("input/unknown-oversized")))
        assertEquals(0, AtomicFileWriter.cleanup(root))
    }

    @Test
    fun aLyingMimeIsRedetectedFromTheRealBytes() {
        val root = scopeRoot("mime")
        val metadata = ContentResolverSafMetadataReader(resolver).metadata(uri("lying-mime"))

        assertEquals("the provider claims a PNG", "image/png", metadata.mimeType)
        val outcome = importPipeline(root).importDocument("ws", uri("lying-mime"), metadata, null, never)

        assertEquals(ImportStatus.COMPLETED, outcome.status)
        assertTrue("the real bytes are text", outcome.isText)
        assertFalse("the reported MIME is never trusted", "image/png" == outcome.mimeType)
    }

    @Test
    fun anEvilDisplayNameIsSanitized() {
        val root = scopeRoot("evil")
        val metadata = ContentResolverSafMetadataReader(resolver).metadata(uri("evil-name"))

        assertTrue(metadata.displayName.orEmpty().contains(LyingContentProvider.EVIL_NAME))
        val outcome = importPipeline(root).importDocument("ws", uri("evil-name"), metadata, null, never)

        assertEquals(ImportStatus.COMPLETED, outcome.status)
        assertEquals(".._.._etc_evilname", outcome.targetName)
        assertTrue(Files.isRegularFile(root.resolve("input/.._.._etc_evilname")))
    }

    @Test
    fun aDeniedSourceIsRefused() {
        val root = scopeRoot("denied")
        val metadata = ContentResolverSafMetadataReader(resolver).metadata(uri("denied"))
        val outcome = importPipeline(root).importDocument("ws", uri("denied"), metadata, null, never)
        assertEquals(ImportRefusal.SOURCE_UNOPENABLE, outcome.refusal)
    }

    // ── 大流取消 ────────────────────────────────────────────────────────────────────────

    @Test
    fun anInfiniteStreamIsCancelledWithoutPublishing() {
        val root = scopeRoot("infinite")
        val metadata = ContentResolverSafMetadataReader(resolver).metadata(uri("infinite"))
        val polls = AtomicInteger(0)
        val token = SafCancelToken { polls.incrementAndGet() >= 5 }
        val outcome = importPipeline(root).importDocument("ws", uri("infinite"), metadata, null, token)

        assertEquals(ImportStatus.CANCELLED, outcome.status)
        assertFalse(Files.exists(root.resolve("input/infinite")))
        assertEquals("the aborted temp must be deleted", 0, AtomicFileWriter.cleanup(root))
    }

    // ── persisted tree grant + 撤销检测 (real resolver probe) ───────────────────────────

    @Test
    fun theGrantProbeDistinguishesGrantedFromDenied() {
        val probe = ContentResolverSafGrantProbe(resolver)
        assertTrue(probe.isStillGranted(uri("granted-tree")))
        assertFalse(probe.isStillGranted(uri("denied-tree")))
    }

    @Test
    fun anEmptyButGrantedTreeIsNotTreatedAsRevoked() {
        val probe = ContentResolverSafGrantProbe(resolver)
        assertTrue(
            "a live grant returns a cursor even when the tree is empty",
            probe.isStillGranted(uri("empty-tree")),
        )
    }

    @Test
    fun theSweepRevokesOnlyTheTreeThatNoLongerAnswers() {
        val store = SafGrantStore(context.filesDir.toPath().resolve("saf-it-sweep/grants.json"))
        store.grant(uri("granted-tree"), "Alive")
        store.grant(uri("denied-tree"), "Dead")

        val revoked = store.sweepRevoked(ContentResolverSafGrantProbe(resolver))

        assertEquals(1, revoked.size)
        assertEquals(uri("denied-tree"), revoked.single().treeUri)
        assertEquals(1, store.list().size)
        // The revocation persisted.
        assertEquals(1, SafGrantStore(context.filesDir.toPath().resolve("saf-it-sweep/grants.json")).list().size)
    }

    // ── export through the real destination adapter ─────────────────────────────────────

    @Test
    fun anExportIntoAPersistedDestinationVerifiesItsSize() {
        val root = scopeRoot("export")
        val payload = "exported through the real resolver\n".toByteArray()
        Files.write(root.resolve("input/report.txt"), payload)

        val pipeline =
            SafExportPipeline(
                ScopeRootResolver { _ -> root },
                ContentResolverSafDestinationOpener(resolver),
                ContentResolverSafDestinationVerifier(resolver),
            )
        val outcome = pipeline.exportFile(FileScopePath("ws", "input/report.txt"), uri("export-sink"), never)

        assertEquals(
            "refusal=${outcome.refusal} detail=${outcome.detail}",
            ExportStatus.COMPLETED,
            outcome.status,
        )
        assertEquals(payload.size.toLong(), outcome.sizeBytes)
        assertEquals(hex(payload), outcome.sha256)
        assertTrue("the honest destination reported the true size", outcome.sizeVerified)
    }
}
