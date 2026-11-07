package com.helix.app.provider

import com.helix.app.vision.ImageReadPolicy
import com.helix.core.model.ArtifactRef
import com.helix.core.model.ImageBinding
import com.helix.core.model.ImageReference
import com.helix.core.model.ImageReselectionRequiredException
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.VisionLimits
import com.helix.core.storage.dao.ArtifactDao
import com.helix.core.storage.entity.ArtifactEntity
import com.helix.core.storage.repository.ArtifactRepository
import com.helix.core.workspace.ContentProbe
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
 * protocol adapters. Fail-closed contract: ONLY request-bound, size- and magic-verified
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

            // Mirror the SQL: the refresh half updates the existing row's content columns,
            // keeping its id (and its registration position); 0 when the path is new.
            override fun refreshBySessionAndPath(
                sessionId: String,
                relativePath: String,
                mediaType: String,
                size: Long,
                sha256: String,
                turnId: String?,
                purpose: String,
            ): Int {
                val existing =
                    rows.values.singleOrNull {
                        it.sessionId == sessionId && it.relativePath == relativePath
                    } ?: return 0
                rows[existing.id] =
                    ArtifactEntity(
                        existing.id,
                        sessionId,
                        relativePath,
                        mediaType,
                        size,
                        sha256,
                        turnId,
                        purpose,
                    )
                return 1
            }

            override fun insertOrIgnore(artifact: ArtifactEntity) {
                rows.putIfAbsent(artifact.id, artifact)
            }

            override fun byId(id: String): ArtifactEntity? = rows[id]

            override fun listBySession(sessionId: String): List<ArtifactEntity> =
                rows.values.filter { it.sessionId == sessionId }

            override fun listByTurn(turnId: String): List<ArtifactEntity> = rows.values.filter { it.turnId == turnId }

            // LinkedHashMap keeps registration order: reversed = the SQL rowid DESC.
            override fun recent(limit: Int): List<ArtifactEntity> = rows.values.reversed().take(limit)

            override fun countByRelativePath(relativePath: String): Int =
                rows.values.count { it.relativePath == relativePath }

            override fun bySessionAndPath(
                sessionId: String,
                relativePath: String,
            ): ArtifactEntity? =
                rows.values.singleOrNull {
                    it.sessionId == sessionId && it.relativePath == relativePath
                }
        }

    private lateinit var source: ArtifactVisionImageSource

    @Before
    fun setUp() {
        root = tmp.newFolder("ws").toPath()
        store = WorkspaceArtifactStore(ScopeRootResolver { _ -> root })
        source =
            ArtifactVisionImageSource(ArtifactRepository(dao()), store) { image, config ->
                requireNotNull(image.binding)
                assertEquals(boundConfig(), config)
                ImageReadPolicy.NORMALIZED_USER_REFERENCE
            }
    }

    /** A byte fixture under the input region, registered with its exact hash and size. */
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
        // The file lands at the bare scope-relative path; the ROW stores the full `scope:` ref
        // (the v16 stored form) that the source parses back via FileScopePath.fromModelReference.
        val entity = ArtifactEntity(id, sessionId, ref(relativePath), "image/jpeg", bytes.size.toLong(), sha)
        rows[id] = entity
        return entity
    }

    /** The v16 stored form of a bare app-scope relative path: its full `scope:` model reference. */
    private fun ref(bare: String): String = FileScopePath("app", bare).toModelReference()

    /** Complete JPEG container framing; pixel decoding is covered by the isolated device fixtures. */
    private fun jpegBytes(): ByteArray =
        byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(),
            0xFF.toByte(),
            0xE0.toByte(),
            0,
            4,
            'J'.code.toByte(),
            'F'.code.toByte(),
            0xFF.toByte(),
            0xDA.toByte(),
            0,
            2,
            17,
            34,
            0xFF.toByte(),
            0xD9.toByte(),
        )

    private fun boundImage(
        id: String,
        sessionId: String = "s1",
    ): ImageReference =
        ImageReference(
            ArtifactRef(id),
            rows[id]?.mediaType ?: "image/jpeg",
            ImageBinding(sessionId, "message-$id", rows[id]?.sha256 ?: "0".repeat(64)),
        )

    @Test
    fun reservedProbeRefsResolveWithoutAnyBindingOrArtifact() {
        listOf(ArtifactVisionImageSource.PROBE_IMAGE_REF, ArtifactVisionImageSource.COLOR_PROBE_REF).forEach { ref ->
            val loaded = source.load(ImageReference(ref, "image/png"), boundConfig())
            assertEquals("image/png", loaded.mediaType)
            val raw = Base64.getDecoder().decode(loaded.base64)
            assertEquals("image/png", ContentProbe.probeBytes(raw, raw.size.toLong()).mimeType)
            assertTrue(raw.size < 512)
        }
    }

    @Test
    fun anImageWithoutRequestBindingFailsClosed() {
        registerImage("art_x", "s1", "input/normalized.jpg", jpegBytes())
        assertIae { source.load(ImageReference(ArtifactRef("art_x"), "image/jpeg"), boundConfig()) }
    }

    @Test
    fun anUnknownRefFailsClosedEvenWithARequestBinding() {
        assertIae { source.load(boundImage("art_missing"), boundConfig()) }
    }

    @Test
    fun aRequestBindingCannotReplaceTheRequiredBindingVerifier() {
        registerImage("art_x", "s1", "input/normalized.jpg", jpegBytes())
        val unverified = ArtifactVisionImageSource(ArtifactRepository(dao()), store)
        assertIae { unverified.load(boundImage("art_x"), boundConfig()) }
    }

    @Test
    fun aRefFromAnotherSessionFailsClosed() {
        registerImage("art_a", "other-session", "input/attachments/att1/normalized.jpg", jpegBytes())
        assertIae { source.load(boundImage("art_a"), boundConfig()) }
    }

    @Test
    fun aVerifiedArtifactResolvesToItsBase64() {
        val bytes = jpegBytes()
        registerImage("art_ok", "s1", "input/attachments/att1/normalized.jpg", bytes)
        val loaded = source.load(boundImage("art_ok"), boundConfig())
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
                ref("input/attachments/att2/normalized.jpg"),
                "image/jpeg",
                (VisionLimits.MAX_NORMALIZED_RAW_BYTES + 1L),
                "0".repeat(64),
            )
        rows[entity.id] = entity
        var reads = 0
        val countedStore =
            WorkspaceArtifactStore(
                ScopeRootResolver {
                    reads++
                    root
                },
            )
        val bounded =
            ArtifactVisionImageSource(ArtifactRepository(dao()), countedStore) { _, _ ->
                ImageReadPolicy.NORMALIZED_USER_REFERENCE
            }
        assertIae { bounded.load(boundImage("art_big"), boundConfig()) }
        assertEquals("over-budget images must fail before file resolution", 0, reads)
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
                ref("input/attachments/att3/normalized.jpg"),
                "image/jpeg",
                png.size.toLong(),
                sha,
            )
        assertIae { source.load(boundImage("art_bad"), boundConfig()) }
    }

    @Test
    fun aVanishedArtifactFileFailsClosed() {
        // The row exists but the file is gone: the bytes do not verify against the snapshot.
        val entity =
            ArtifactEntity(
                "art_gone",
                "s1",
                ref("input/attachments/att4/normalized.jpg"),
                "image/jpeg",
                9L,
                AtomicSha.sha(jpegBytes()),
            )
        rows[entity.id] = entity
        assertIae { source.load(boundImage("art_gone"), boundConfig()) }
    }

    @Test
    fun aPathEscapingTheScopeFailsClosed() {
        // A stored full ref whose path escapes the scope must never read: fromModelReference
        // re-validates the path through FileScopePath, so an escaping `..` segment is refused
        // before any bytes are read. A real sink can never persist such a ref (FileScopePath
        // construction rejects it) — this is defense in depth against a corrupted row.
        val entity =
            ArtifactEntity(
                "art_escape",
                "s1",
                "scope:app:../../escape.jpg",
                "image/jpeg",
                9L,
                AtomicSha.sha(jpegBytes()),
            )
        rows[entity.id] = entity
        assertIae { source.load(boundImage("art_escape"), boundConfig()) }
    }

    @Test
    fun eachRequestCarriesItsOwnSessionAndHash() {
        val entity = registerImage("image", "s1", "input/bound.jpg", jpegBytes())
        val source =
            ArtifactVisionImageSource(ArtifactRepository(dao()), store) { _, _ -> ImageReadPolicy.TOOL_OBSERVATION }
        val image =
            com.helix.core.model.ImageReference(
                ArtifactRef(entity.id),
                entity.mediaType,
                com.helix.core.model
                    .ImageBinding("s1", "message", entity.sha256),
            )
        val loaded = source.load(image, boundConfig())
        assertEquals(jpegBytes().toList(), Base64.getDecoder().decode(loaded.base64).toList())
        assertIae { source.load(image.copy(binding = image.binding!!.copy(sessionId = "other")), boundConfig()) }
        assertIae { source.load(image.copy(binding = image.binding!!.copy(sha256 = "0".repeat(64))), boundConfig()) }
        assertIae { source.load(image.copy(mediaType = "image/png"), boundConfig()) }
    }

    @Test
    fun sameSizeTamperingIsRejectedAtTheFinalByteBoundary() {
        val entity = registerImage("image", "s1", "input/bound.jpg", jpegBytes())
        val source =
            ArtifactVisionImageSource(ArtifactRepository(dao()), store) { _, _ -> ImageReadPolicy.TOOL_OBSERVATION }
        val image =
            com.helix.core.model.ImageReference(
                ArtifactRef(entity.id),
                entity.mediaType,
                com.helix.core.model
                    .ImageBinding("s1", "message", entity.sha256),
            )
        val changed = jpegBytes().also { it[it.lastIndex] = 7 }
        Files.write(root.resolve("input/bound.jpg"), changed)
        assertIae { source.load(image, boundConfig()) }
    }

    @Test
    fun revocationDuringMaterializationPreventsReturningPixelPayload() {
        val entity = registerImage("image", "s1", "input/bound.jpg", jpegBytes())
        var checks = 0
        val source =
            ArtifactVisionImageSource(ArtifactRepository(dao()), store) { _, _ ->
                checks++
                require(checks == 1) { "Consent revoked" }
                ImageReadPolicy.TOOL_OBSERVATION
            }
        val image =
            com.helix.core.model.ImageReference(
                ArtifactRef(entity.id),
                entity.mediaType,
                com.helix.core.model
                    .ImageBinding("s1", "message", entity.sha256),
            )
        assertIae { source.load(image, boundConfig()) }
        assertEquals(2, checks)
    }

    @Test
    fun exifRuleIsOnlyAppliedToVerifiedNormalizedUserReferences() {
        val metadata = "Exif\u0000\u0000private-gps-fixture".toByteArray()
        val marker = byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, (metadata.size + 2).toByte())
        val bytes = jpegBytes().take(2).toByteArray() + marker + metadata + jpegBytes().drop(2).toByteArray()
        val entity = registerImage("metadata", "s1", "input/metadata.jpg", bytes)
        val image =
            com.helix.core.model.ImageReference(
                ArtifactRef(entity.id),
                entity.mediaType,
                com.helix.core.model
                    .ImageBinding("s1", "message", entity.sha256),
            )
        val tool =
            ArtifactVisionImageSource(ArtifactRepository(dao()), store) { _, _ -> ImageReadPolicy.TOOL_OBSERVATION }
        assertEquals(bytes.toList(), Base64.getDecoder().decode(tool.load(image, boundConfig()).base64).toList())
        val reference =
            ArtifactVisionImageSource(
                ArtifactRepository(dao()),
                store,
            ) { _, _ -> ImageReadPolicy.NORMALIZED_USER_REFERENCE }
        val failure =
            assertThrows(ImageReselectionRequiredException::class.java) {
                reference.load(image, boundConfig())
            }
        assertEquals(ModelErrorCode.IMAGE_RESELECT_REQUIRED.name, failure.message)
    }

    private fun boundConfig() =
        com.helix.provider.api.ProviderConfig(
            "p",
            "Vision",
            com.helix.core.model.ProviderProtocol.OPENAI_RESPONSES,
            com.helix.core.model.NormalizedEndpoint
                .parse("https://example.com/v1"),
            "vision",
            emptyMap(),
            com.helix.core.model
                .SecretAlias("fixture"),
            "{}",
        )

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
