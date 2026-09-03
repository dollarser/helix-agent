package com.helix.feature.files

import com.helix.core.workspace.ScopeRootResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * HXA-058: the OPTIONAL byte-progress callbacks added to the HXA-044 pipelines. They are
 * informational (the file-manager UI's progress bar) and never change the HXA-044 contract:
 * admission, the hard cap, the EOF size re-verification, the atomic publish and the refusal
 * mapping are all unchanged (covered by [SafImportPipelineTest] / [SafExportPipelineTest]).
 */
class SafPipelineProgressTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val never = SafCancelToken { false }

    // ── Import progress ──────────────────────────────────────────────────────────────────

    @Test
    fun importProgressReportsCumulativeBytesAndTheExpectedTotal() {
        val root = tmp.newFolder("ws").toPath()
        Files.createDirectories(root.resolve("input"))
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        val points = mutableListOf<Pair<Long, Long>>()
        val pipeline =
            SafImportPipeline(ScopeRootResolver { _ -> root }, SafSourceOpener { ByteArrayInputStream(bytes) })
        val outcome =
            pipeline.importDocument(
                "ws",
                "content://p/doc",
                SafSourceMetadata(bytes.size.toLong(), null, "doc.bin"),
                null,
                never,
                onProgress = { done, total -> points += done to total },
            )
        assertEquals(ImportStatus.COMPLETED, outcome.status)
        assertTrue("progress is reported per chunk", points.size > 1)
        assertEquals(bytes.size.toLong(), points.last().first)
        assertEquals("a known reported size is the expected total", bytes.size.toLong(), points.last().second)
        assertTrue("progress is cumulative", points.zipWithNext().all { (a, b) -> b.first >= a.first })
    }

    @Test
    fun importProgressReportsUnknownTotalWhenTheProviderReportsNoSize() {
        val root = tmp.newFolder("ws").toPath()
        Files.createDirectories(root.resolve("input"))
        val bytes = "abc".toByteArray()
        var total = 42L
        val pipeline =
            SafImportPipeline(ScopeRootResolver { _ -> root }, SafSourceOpener { ByteArrayInputStream(bytes) })
        pipeline.importDocument(
            "ws",
            "content://p/doc",
            SafSourceMetadata(-1L, null, "doc.txt"),
            null,
            never,
            onProgress = { _, t -> total = t },
        )
        assertEquals("an unknown size reports -1 as the total", -1L, total)
    }

    // ── Export progress ──────────────────────────────────────────────────────────────────

    @Test
    fun exportProgressReportsCumulativeBytesAndTheSourceSize() {
        val root = tmp.newFolder("ws").toPath()
        Files.createDirectories(root.resolve("input"))
        val bytes = ByteArray(300_000) { (it % 251).toByte() }
        Files.write(root.resolve("input/big.bin"), bytes)
        val points = mutableListOf<Long>()
        val sink = java.io.ByteArrayOutputStream()
        val pipeline =
            SafExportPipeline(
                ScopeRootResolver { _ -> root },
                SafDestinationOpener { sink },
                SafDestinationVerifier { sink.size().toLong() },
            )
        val outcome =
            pipeline.exportFile(
                com.helix.core.workspace
                    .FileScopePath("ws", "input/big.bin"),
                "content://p/sink",
                never,
                onProgress = { done, total ->
                    points += done
                    assertEquals(bytes.size.toLong(), total)
                },
            )
        assertEquals(ExportStatus.COMPLETED, outcome.status)
        assertTrue(points.size > 1)
        assertEquals(bytes.size.toLong(), points.last())
    }

    @Test
    fun aCancelledExportStopsReportingProgress() {
        val root = tmp.newFolder("ws").toPath()
        Files.createDirectories(root.resolve("input"))
        val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        Files.write(root.resolve("input/big.bin"), bytes)
        val sink = java.io.ByteArrayOutputStream()
        val cancel = AtomicBoolean(false)
        val reported = mutableListOf<Long>()
        val pipeline =
            SafExportPipeline(
                ScopeRootResolver { _ -> root },
                SafDestinationOpener { sink },
                SafDestinationVerifier { sink.size().toLong() },
            )
        val outcome =
            pipeline.exportFile(
                com.helix.core.workspace
                    .FileScopePath("ws", "input/big.bin"),
                "content://p/sink",
                SafCancelToken { cancel.get() },
                onProgress = { done, _ ->
                    reported += done
                    if (done >= 128 * 1024) cancel.set(true)
                },
            )
        assertEquals(ExportStatus.CANCELLED, outcome.status)
        assertTrue("the cancel fires mid-copy, not at the end", reported.last() < bytes.size.toLong())
    }
}
