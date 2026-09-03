package com.helix.app.provider

import com.helix.core.model.ArtifactRef
import com.helix.core.model.VisionLimits
import com.helix.core.storage.dao.ArtifactDao
import com.helix.core.storage.entity.ArtifactEntity
import com.helix.core.storage.repository.ArtifactRepository
import com.helix.core.workspace.ContentProbe
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

/**
 * HXA-055: [ArtifactVisionImageSource] — the single production image source of the three
 * protocol adapters. Fail-closed contract: ONLY session-bound, size- and magic-verified
 * app-private artifacts resolve; every miss throws a stable, path-free IAE (the encoders
 * propagate it, the turn fails with an actionable error). The reserved probe ref resolves
 * without any session artifact.
 */
class ArtifactVisionImageSourceTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: Path
    private lateinit var store: WorkspaceArtifactStore
    private val rows = LinkedHashMap<String, ArtifactEntity>()

    private fun dao(): ArtifactDao =
        object : ArtifactDao {
            override fun insert(artifact: ArtifactEntity) {
                rows[artifact.id] = artifact
            }

            override fun byId(id: String): ArtifactEntity? = rows[id]

            override fun listBySession(sessionId: String): List<ArtifactEntity> =
                rows.values.filter { it.sessionId == sessionId }
        }

    private lateinit var source: ArtifactVisionImageSource

    @Before
    fun setUp() {
        root = tmp.newFolder("ws").toPath()
        store = WorkspaceArtifactStore(ScopeRootResolver { _ -> root })
        source = ArtifactVisionImageSource(ArtifactRepository(dao()), store, "app")
    }

    /** A real JPEG file (FF D8 FF magic) under the input region, registered as an artifact. */
    private fun registerImage(
        id: String,
        sessionId: String,
        relativePath: String,
        bytes: ByteArray,
    ): ArtifactEntity {
        val file = root.resolve(relativePath)
        Files.createDirectories(file.parent)
        Files.write(file, bytes)
        val sha = AtomicSha.sha(bytes)
        val entity = ArtifactEntity(id, sessionId, relativePath, "image/jpeg", bytes.size.toLong(), sha)
        rows[id] = entity
        return entity
    }

    private fun jpegBytes(): ByteArray =
        byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(),
            0xFF.toByte(),
            0xE0.toByte(),
            0,
            16,
            'J'.code.toByte(),
            'F'.code.toByte(),
            0,
            1,
        )

    @Test
    fun theReservedProbeRefResolvesWithoutAnySessionOrArtifact() {
        val loaded = source.load(ArtifactVisionImageSource.PROBE_IMAGE_REF)
        assertEquals("image/png", loaded.mediaType)
        // The probe image is a VALID 1x1 PNG — the magic bytes verify after decode.
        val raw = Base64.getDecoder().decode(loaded.base64)
        assertEquals("image/png", ContentProbe.probeBytes(raw, raw.size.toLong()).mimeType)
        assertTrue(raw.size < 512)
    }

    @Test
    fun anUnboundSessionFailsClosed() {
        assertIae { source.load(ArtifactRef("art_x")) }
    }

    @Test
    fun anUnknownRefFailsClosedEvenWithABoundSession() {
        source.bindSession("s1")
        assertIae { source.load(ArtifactRef("art_missing")) }
    }

    @Test
    fun aRefFromAnotherSessionFailsClosed() {
        registerImage("art_a", "other-session", "input/attachments/att1/normalized.jpg", jpegBytes())
        source.bindSession("s1")
        assertIae { source.load(ArtifactRef("art_a")) }
    }

    @Test
    fun aVerifiedArtifactResolvesToItsBase64() {
        val bytes = jpegBytes()
        registerImage("art_ok", "s1", "input/attachments/att1/normalized.jpg", bytes)
        source.bindSession("s1")
        val loaded = source.load(ArtifactRef("art_ok"))
        assertEquals("image/jpeg", loaded.mediaType)
        assertEquals(Base64.getEncoder().encodeToString(bytes), loaded.base64)
    }

    @Test
    fun anOverBudgetArtifactFailsClosedBeforeAnyRead() {
        // A row whose size exceeds the per-image budget is refused before the bytes are read.
        val entity =
            ArtifactEntity(
                "art_big",
                "s1",
                "input/attachments/att2/normalized.jpg",
                "image/jpeg",
                (VisionLimits.MAX_NORMALIZED_RAW_BYTES + 1L),
                "0".repeat(64),
            )
        rows[entity.id] = entity
        source.bindSession("s1")
        assertIae { source.load(ArtifactRef("art_big")) }
    }

    @Test
    fun aRelabeledArtifactFailsClosedOnTheMagicMismatch() {
        // The registered type says jpeg but the BYTES are PNG — the MIME/signature
        // consistency gate refuses: a relabeled file never reaches the wire.
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0)
        val file = root.resolve("input/attachments/att3/normalized.jpg")
        Files.createDirectories(file.parent)
        Files.write(file, png)
        val sha = AtomicSha.sha(png)
        rows["art_bad"] =
            ArtifactEntity(
                "art_bad",
                "s1",
                "input/attachments/att3/normalized.jpg",
                "image/jpeg",
                png.size.toLong(),
                sha,
            )
        source.bindSession("s1")
        assertIae { source.load(ArtifactRef("art_bad")) }
    }

    @Test
    fun aVanishedArtifactFileFailsClosed() {
        // The row exists but the file is gone: the bytes do not verify against the snapshot.
        val entity =
            ArtifactEntity(
                "art_gone",
                "s1",
                "input/attachments/att4/normalized.jpg",
                "image/jpeg",
                9L,
                AtomicSha.sha(jpegBytes()),
            )
        rows[entity.id] = entity
        source.bindSession("s1")
        assertIae { source.load(ArtifactRef("art_gone")) }
    }

    @Test
    fun aPathEscapingTheScopeFailsClosed() {
        // A registered relativePath that escapes the scope (../../etc) must never read.
        val entity =
            ArtifactEntity(
                "art_escape",
                "s1",
                "../../escape.jpg",
                "image/jpeg",
                9L,
                AtomicSha.sha(jpegBytes()),
            )
        rows[entity.id] = entity
        source.bindSession("s1")
        assertIae { source.load(ArtifactRef("art_escape")) }
    }

    private fun assertIae(block: () -> Unit) {
        var thrown: Throwable? = null
        try {
            block()
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("expected a fail-closed IAE, got: $thrown", thrown is IllegalArgumentException)
        // The message is path-free by contract (never a real filesystem path).
        assertTrue(thrown!!.message!!.none { it == '/' })
    }

    private object AtomicSha {
        fun sha(bytes: ByteArray): String =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
