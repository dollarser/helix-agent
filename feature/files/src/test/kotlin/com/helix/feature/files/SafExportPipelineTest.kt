package com.helix.feature.files

import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * HXA-044: [SafExportPipeline] — exporting a workspace file to a user-chosen SAF destination,
 * fail-closed (a lying destination is as untrusted as a lying source, doc 07).
 *
 * Axes covered:
 * - the region gate: only `input/` / `work/` / `output/` are exportable, `.helix/` never;
 * - a normal export hashes the streamed bytes and is size-verified against the destination's
 *   post-write report; a lying (or unknown) report degrades to mismatch / unverified;
 * - cancellation (before start and mid-copy) publishes no full document — a mid-copy cancel
 *   reports the partial-document consequence;
 * - missing / directory / oversized sources and an unopenable destination are refused.
 */
class SafExportPipelineTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val never = SafCancelToken { false }

    private fun hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** A scope root holding [payload] at `<root>/<region>/<name>`; returns the root. */
    private fun scopeWith(
        region: String,
        name: String,
        payload: ByteArray,
    ): Path {
        // Unnamed: a test may build several scope roots (the region loop does).
        val root = tmp.newFolder().toPath()
        val dir = root.resolve(region)
        Files.createDirectories(dir)
        Files.write(dir.resolve(name), payload)
        return root
    }

    private fun source(
        region: String,
        name: String,
    ) = FileScopePath("ws", "$region/$name")

    /**
     * A pipeline whose destination records everything written. [verify] maps the destination's
     * observed byte count to the size it REPORTS post-write; [failOpen] makes open() throw.
     */
    private fun pipeline(
        root: Path,
        failOpen: Boolean = false,
        verify: (Long) -> Long,
    ): Pair<SafExportPipeline, ByteArrayOutputStream> {
        val sink = ByteArrayOutputStream()
        val destination =
            SafDestinationOpener {
                if (failOpen) throw SecurityException("no write access")
                sink
            }
        val verifier = SafDestinationVerifier { verify(sink.size().toLong()) }
        return SafExportPipeline(ScopeRootResolver { _ -> root }, destination, verifier) to sink
    }

    // ── Region gate ─────────────────────────────────────────────────────────────────────

    @Test
    fun onlyUserRegionsAreExportable() {
        val root = tmp.newFolder("scope").toPath()
        Files.createDirectories(root.resolve(WorkspaceLayout.HELIX))
        Files.write(root.resolve(WorkspaceLayout.HELIX).resolve("metadata.json"), "secret".toByteArray())
        val (pipeline, sink) = pipeline(root) { it }

        val outcome = pipeline.exportFile(source(WorkspaceLayout.HELIX, "metadata.json"), "content://dest/x", never)

        assertEquals(ExportRefusal.OUTSIDE_USER_REGIONS, outcome.refusal)
        assertEquals("nothing may be written", 0, sink.size())
    }

    @Test
    fun inputWorkAndOutputAreAllExportable() {
        for (region in listOf(WorkspaceLayout.INPUT, WorkspaceLayout.WORK, WorkspaceLayout.OUTPUT)) {
            val root = scopeWith(region, "data.bin", "bytes".toByteArray())
            val (pipeline, sink) = pipeline(root) { it }
            val outcome = pipeline.exportFile(source(region, "data.bin"), "content://dest/x", never)
            assertEquals("region $region must be exportable", ExportStatus.COMPLETED, outcome.status)
            assertEquals("bytes", String(sink.toByteArray()))
        }
    }

    // ── Normal path + verification ──────────────────────────────────────────────────────

    @Test
    fun aNormalExportStreamsTheExactBytesAndVerifiesTheSize() {
        val payload = "export me 0123456789".toByteArray()
        val root = scopeWith(WorkspaceLayout.WORK, "report.txt", payload)
        val (pipeline, sink) = pipeline(root) { it }

        val outcome = pipeline.exportFile(source(WorkspaceLayout.WORK, "report.txt"), "content://dest/x", never)

        assertEquals(ExportStatus.COMPLETED, outcome.status)
        assertEquals(payload.size.toLong(), outcome.sizeBytes)
        assertEquals(hex(payload), outcome.sha256)
        assertTrue(outcome.sizeVerified)
        assertEquals(payload.toList(), sink.toByteArray().toList())
        // The source survives its export.
        assertTrue(Files.isRegularFile(root.resolve("work/report.txt")))
    }

    @Test
    fun aDestinationThatReportsTheWrongSizeIsRefused() {
        val root = scopeWith(WorkspaceLayout.INPUT, "data.bin", "payload".toByteArray())
        val (pipeline, _) = pipeline(root) { it + 1000 }

        val outcome = pipeline.exportFile(source(WorkspaceLayout.INPUT, "data.bin"), "content://dest/x", never)

        assertEquals(ExportRefusal.SIZE_VERIFICATION_MISMATCH, outcome.refusal)
        assertFalse(outcome.sizeVerified)
    }

    @Test
    fun aDestinationThatReportsNoSizeCompletesAsUnverified() {
        val payload = "payload".toByteArray()
        val root = scopeWith(WorkspaceLayout.INPUT, "data.bin", payload)
        val (pipeline, _) = pipeline(root) { -1L }

        val outcome = pipeline.exportFile(source(WorkspaceLayout.INPUT, "data.bin"), "content://dest/x", never)

        assertEquals(ExportStatus.COMPLETED, outcome.status)
        assertFalse("an unknown report must not claim verification", outcome.sizeVerified)
        assertEquals(payload.size.toLong(), outcome.sizeBytes)
    }

    // ── Cancellation ────────────────────────────────────────────────────────────────────

    @Test
    fun aCancelBeforeStartNeverOpensTheDestination() {
        val root = scopeWith(WorkspaceLayout.INPUT, "data.bin", "x".toByteArray())
        var opened = false
        val destination =
            SafDestinationOpener {
                opened = true
                ByteArrayOutputStream()
            }
        val pipeline =
            SafExportPipeline(ScopeRootResolver { _ -> root }, destination, SafDestinationVerifier { -1L })
        val outcome =
            pipeline.exportFile(source(WorkspaceLayout.INPUT, "data.bin"), "content://dest/x", SafCancelToken { true })
        assertEquals(ExportStatus.CANCELLED, outcome.status)
        assertFalse(opened)
    }

    @Test
    fun aCancelMidCopyReportsThePartialDocumentConsequence() {
        // A 256 KiB source is copied in 64 KiB chunks; the token cancels once the destination
        // has observed a little more than one chunk, so the copy dies between chunks.
        val payload = ByteArray(256 * 1024) { it.toByte() }
        val root = scopeWith(WorkspaceLayout.OUTPUT, "big.bin", payload)
        val sink = ByteArrayOutputStream()
        var bytesSeen = 0
        val destination =
            SafDestinationOpener {
                object : OutputStream() {
                    override fun write(b: Int) {
                        bytesSeen++
                        sink.write(b)
                    }

                    override fun write(
                        b: ByteArray,
                        off: Int,
                        len: Int,
                    ) {
                        bytesSeen += len
                        sink.write(b, off, len)
                    }
                }
            }
        val pipeline =
            SafExportPipeline(
                ScopeRootResolver { _ -> root },
                destination,
                SafDestinationVerifier { -1L },
            )
        val token = SafCancelToken { bytesSeen > 64 * 1024 }
        val outcome = pipeline.exportFile(source(WorkspaceLayout.OUTPUT, "big.bin"), "content://dest/x", token)

        assertEquals(ExportStatus.CANCELLED, outcome.status)
        assertTrue("a partial document is acknowledged", outcome.detail.orEmpty().contains("partial"))
        assertTrue("some bytes did go out", bytesSeen > 64 * 1024)
        assertTrue("the destination holds a partial document", bytesSeen < payload.size)
        assertNull(outcome.sha256)
    }

    // ── Failure paths ───────────────────────────────────────────────────────────────────

    @Test
    fun aMissingSourceIsRefused() {
        val root = tmp.newFolder("scope").toPath()
        Files.createDirectories(root.resolve(WorkspaceLayout.INPUT))
        val (pipeline, sink) = pipeline(root) { it }
        val outcome = pipeline.exportFile(source(WorkspaceLayout.INPUT, "absent.bin"), "content://dest/x", never)
        assertEquals(ExportRefusal.SOURCE_NOT_FOUND, outcome.refusal)
        assertEquals(0, sink.size())
    }

    @Test
    fun aDirectorySourceIsRefusedAsNotAFile() {
        val root = tmp.newFolder("scope").toPath()
        Files.createDirectories(root.resolve(WorkspaceLayout.INPUT).resolve("adir"))
        val (pipeline, _) = pipeline(root) { it }
        val outcome = pipeline.exportFile(source(WorkspaceLayout.INPUT, "adir"), "content://dest/x", never)
        assertEquals(ExportRefusal.NOT_A_FILE, outcome.refusal)
    }

    @Test
    fun anOversizedSourceIsRefusedBeforeOpeningTheDestination() {
        val root = scopeWith(WorkspaceLayout.INPUT, "big.bin", ByteArray(2048))
        var opened = false
        val destination =
            SafDestinationOpener {
                opened = true
                ByteArrayOutputStream()
            }
        val pipeline =
            SafExportPipeline(
                ScopeRootResolver { _ -> root },
                destination,
                SafDestinationVerifier { -1L },
                maxExportBytes = 1024L,
            )
        val outcome = pipeline.exportFile(source(WorkspaceLayout.INPUT, "big.bin"), "content://dest/x", never)
        assertEquals(ExportRefusal.SOURCE_EXCEEDS_LIMIT, outcome.refusal)
        assertFalse(opened)
    }

    @Test
    fun anUnopenableDestinationIsRefused() {
        val root = scopeWith(WorkspaceLayout.INPUT, "data.bin", "x".toByteArray())
        val (pipeline, _) = pipeline(root, verify = { it }, failOpen = true)
        val outcome = pipeline.exportFile(source(WorkspaceLayout.INPUT, "data.bin"), "content://dest/x", never)
        assertEquals(ExportRefusal.DESTINATION_UNOPENABLE, outcome.refusal)
    }
}
