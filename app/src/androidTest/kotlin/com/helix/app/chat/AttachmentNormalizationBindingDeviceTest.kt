package com.helix.app.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.infrastructure.chat.AttachmentStagingSupport
import com.helix.app.infrastructure.chat.StagedAttachmentEntry
import com.helix.app.infrastructure.chat.StagedAttachmentProcessor
import com.helix.app.provider.ArtifactVisionImageSource
import com.helix.app.vision.BoundImageAccess
import com.helix.app.vision.ToolVisionConsent
import com.helix.core.model.ArtifactRef
import com.helix.core.model.AttachmentPurpose
import com.helix.core.model.ImageBinding
import com.helix.core.model.ImageReference
import com.helix.core.model.ImageReselectionRequiredException
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ProviderAuth
import com.helix.core.model.ProviderConnection
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderTransport
import com.helix.core.model.SystemClock
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.repository.MessageAttachmentRepository
import com.helix.core.workspace.FileScopePath
import com.helix.core.workspace.ScopeRootResolver
import com.helix.core.workspace.WorkspaceArtifactStore
import com.helix.core.workspace.resolveFileScopePath
import com.helix.feature.files.AttachmentImportResult
import com.helix.feature.files.AttachmentImporter
import com.helix.feature.files.ImportStatus
import com.helix.feature.files.SafCancelToken
import com.helix.feature.files.SafImportPipeline
import com.helix.feature.files.SafSourceMetadata
import com.helix.feature.files.SafSourceOpener
import com.helix.provider.api.ProviderConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only fixture-owned storage and files: no application container, activity, services or real provider. */
@RunWith(AndroidJUnit4::class)
class AttachmentNormalizationBindingDeviceTest {
    @Test
    fun normalizedJpegNameCannotReuseRawExifAsProviderImage() {
        Fixture().use { fixture ->
            val imported = fixture.importJpeg("normalized.jpg")
            val entry = fixture.prepare(imported)

            assertNormalized(fixture, entry)
            assertRawPreserved(fixture, imported, entry)
            val request = fixture.bind(entry)
            assertProviderImageIsPrivate(fixture, request)

            // A valid raw artifact is still not the normalized artifact bound to this message.
            val rawRequest =
                request.copy(
                    ref = ArtifactRef(entry.artifactId),
                    binding = requireNotNull(request.binding).copy(sha256 = entry.boundSha256),
                )
            assertRejected { fixture.imageSource().load(rawRequest, fixture.provider) }
        }
    }

    @Test
    fun extensionlessDerivedNameAndNormalizedJpegHaveIndependentArtifacts() {
        Fixture().use { fixture ->
            val imports = listOf(fixture.importJpeg("derived"), fixture.importJpeg("normalized.jpg"))
            val entries = imports.map(fixture::prepare)

            assertEquals(listOf("derived", "normalized.jpg"), entries.map { it.fileName })
            assertEquals(2, entries.map { it.normalizedArtifactId }.toSet().size)
            assertEquals(
                4,
                fixture.storage.artifacts
                    .listBySession(fixture.sessionId)
                    .size,
            )
            entries.zip(imports).forEach { (entry, imported) ->
                assertNormalized(fixture, entry)
                assertRawPreserved(fixture, imported, entry)
                assertProviderImageIsPrivate(fixture, fixture.bind(entry))
            }
        }
    }

    @Test
    @Suppress("DEPRECATION") // WEBP is also supported by the API 29 fixture host.
    fun realJpegPngAndWebpReachTheBoundProviderWithoutExifScannerFalsePositives() {
        Fixture().use { fixture ->
            val formats =
                listOf(
                    Bitmap.CompressFormat.JPEG to "image/jpeg",
                    Bitmap.CompressFormat.PNG to "image/png",
                    Bitmap.CompressFormat.WEBP to "image/webp",
                )
            for ((format, mediaType) in formats) {
                val raw = createBitmapImage(format)
                val imported = fixture.importImage("normalized.${mediaType.substringAfter('/')}", mediaType, raw)
                val entry = fixture.prepare(imported)
                assertNull("$mediaType must normalize", entry.imageSendError)
                assertEquals(mediaType, entry.normalizedMediaType)
                assertNotEquals(entry.artifactId, entry.normalizedArtifactId)
                assertNotEquals(entry.file, entry.normalizedFile)
                assertArrayEquals(raw, Files.readAllBytes(entry.file))
                assertEquals(imported.sha256, FileContentStore.sha256Hex(entry.file.toFile()))

                val request = fixture.bind(entry)
                val binding = requireNotNull(request.binding)
                assertEquals(
                    "USER",
                    fixture.storage.messages
                        .resolve(binding.messageId)
                        .role,
                )
                val attachment =
                    fixture.storage.messageAttachments
                        .listByMessage(binding.messageId)
                        .single()
                assertEquals(AttachmentPurpose.REFERENCE, attachment.purpose)
                assertEquals(entry.normalizedArtifactId, attachment.artifactId)
                val loaded = fixture.imageSource().load(request, fixture.provider)
                assertEquals(mediaType, loaded.mediaType)
                val bytes = Base64.getDecoder().decode(loaded.base64)
                assertArrayEquals(Files.readAllBytes(requireNotNull(entry.normalizedFile)), bytes)
                assertEquals(binding.sha256, FileContentStore.sha256Hex(bytes))
                val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                try {
                    assertEquals(32, decoded.width)
                    assertEquals(16, decoded.height)
                } finally {
                    decoded.recycle()
                }
                val restored = requireNotNull(fixture.processor().restoreEntry(entry.artifactId, fixture.sessionId))
                assertNull(restored.imageSendError)
                assertEquals(entry.normalizedArtifactId, restored.normalizedArtifactId)
                assertEquals(entry.normalizedSha256, restored.normalizedSha256)
            }
        }
    }

    @Test
    fun repeatedRestoreAfterReopenRetainsTheBoundWinner() {
        Fixture().use { fixture ->
            val imported = fixture.importJpeg("normalized.jpg")
            val original = fixture.prepare(imported)
            val request = fixture.bind(original)
            val normalizedBytes = Files.readAllBytes(requireNotNull(original.normalizedFile))
            val before = fixture.storage.artifacts.listBySession(fixture.sessionId)
            val filesBefore = regularFiles(fixture.workspaceRoot)

            fixture.reopen()
            repeat(3) {
                val restored = requireNotNull(fixture.processor().restoreEntry(original.artifactId, fixture.sessionId))
                assertEquals(original.normalizedArtifactId, restored.normalizedArtifactId)
                assertEquals(original.normalizedSha256, restored.normalizedSha256)
                assertEquals(original.normalizedFile, restored.normalizedFile)
                assertArrayEquals(normalizedBytes, Files.readAllBytes(requireNotNull(restored.normalizedFile)))
                assertRawPreserved(fixture, imported, restored)
                assertProviderImageIsPrivate(fixture, request)
            }
            assertEquals(before, fixture.storage.artifacts.listBySession(fixture.sessionId))
            assertEquals(filesBefore, regularFiles(fixture.workspaceRoot))
        }
    }

    @Test
    fun derivedDirectoryFailurePreservesRawAndCanBeRestoredLater() {
        Fixture().use { fixture ->
            val imported = fixture.importJpeg("normalized.jpg")
            val blocker = fixture.workspaceRoot.resolve("input/attachment-derived")
            val sentinel = "fixture-owned directory blocker".toByteArray()
            Files.write(blocker, sentinel)

            val failed = fixture.prepare(imported)
            assertNotNull(failed.imageSendError)
            assertNull(failed.normalizedArtifactId)
            assertNull(failed.normalizedSha256)
            assertNull(failed.normalizedFile)
            assertEquals(
                1,
                fixture.storage.artifacts
                    .listBySession(fixture.sessionId)
                    .size,
            )
            assertRawPreserved(fixture, imported, failed)
            assertArrayEquals(sentinel, Files.readAllBytes(blocker))

            Files.delete(blocker)
            val restored = requireNotNull(fixture.processor().restoreEntry(failed.artifactId, fixture.sessionId))
            assertNormalized(fixture, restored)
            assertRawPreserved(fixture, imported, restored)
            assertProviderImageIsPrivate(fixture, fixture.bind(restored))
        }
    }

    @Test
    fun concurrentRestoresPublishOneWinnerWithoutChangingRawOrBoundBytes() {
        Fixture().use { fixture ->
            val imported = fixture.importJpeg("normalized.jpg")
            val rawId = "art_${UUID.randomUUID()}"
            val rawFile = fixture.resolve(requireNotNull(imported.modelRef))
            fixture.storage.artifacts.register(
                rawId,
                fixture.sessionId,
                requireNotNull(imported.modelRef),
                "image/jpeg",
                imported.sizeBytes,
                requireNotNull(imported.sha256),
                rawFile.toFile(),
            )
            // Both restorers miss the registry before either starts encoding.
            val rendezvous = CyclicBarrier(2)
            val executor = Executors.newFixedThreadPool(2)
            fixture.workersStopped = false
            val restored =
                try {
                    val jobs =
                        List(2) {
                            executor.submit<StagedAttachmentEntry> {
                                val processor =
                                    fixture.processor { path ->
                                        if (path.relativePath.startsWith("input/attachment-derived/") &&
                                            path.relativePath.count { it == '/' } == 2
                                        ) {
                                            rendezvous.await(10, TimeUnit.SECONDS)
                                        }
                                    }
                                requireNotNull(processor.restoreEntry(rawId, fixture.sessionId))
                            }
                        }
                    jobs.map { it.get(20, TimeUnit.SECONDS) }
                } finally {
                    executor.shutdownNow()
                    fixture.workersStopped = executor.awaitTermination(10, TimeUnit.SECONDS)
                    assertTrue("Restorers did not stop; private fixture retained", fixture.workersStopped)
                }

            val winner = restored.first()
            restored.forEach { entry ->
                assertNormalized(fixture, entry)
                assertEquals(winner.normalizedArtifactId, entry.normalizedArtifactId)
                assertEquals(winner.normalizedSha256, entry.normalizedSha256)
                assertEquals(winner.normalizedFile?.toRealPath(), entry.normalizedFile?.toRealPath())
                assertRawPreserved(fixture, imported, entry)
            }
            assertEquals(
                2,
                fixture.storage.artifacts
                    .listBySession(fixture.sessionId)
                    .size,
            )
            val expectedFiles = setOf(rawFile.toRealPath(), requireNotNull(winner.normalizedFile).toRealPath())
            assertEquals(expectedFiles, regularFiles(fixture.workspaceRoot))
            assertProviderImageIsPrivate(fixture, fixture.bind(winner))
        }
    }

    @Test
    fun rawChangeDuringNormalizationCannotPublishUnderTheAcceptedHash() {
        Fixture().use { fixture ->
            val imported = fixture.importJpeg("normalized.jpg")
            val raw = fixture.resolve(requireNotNull(imported.modelRef))
            val changed = fixture.jpegBytes + byteArrayOf(0)
            var mutated = false
            val processor =
                fixture.processor { path ->
                    if (!mutated && path.relativePath.startsWith("input/attachment-derived/")) {
                        mutated = true
                        Files.write(raw, changed)
                    }
                }
            val prepared = processor.prepare(imported, fixture.sessionId) as StagedAttachmentProcessor.Preparation.Ready
            assertTrue(mutated)
            assertNotNull(prepared.entry.imageSendError)
            assertNull(prepared.entry.normalizedArtifactId)
            assertEquals(
                1,
                fixture.storage.artifacts
                    .listBySession(fixture.sessionId)
                    .size,
            )
            assertEquals(
                imported.sha256,
                fixture.storage.artifacts
                    .resolve(prepared.entry.artifactId)
                    .sha256,
            )
            assertArrayEquals(changed, Files.readAllBytes(raw))
            assertEquals(setOf(raw.toRealPath()), regularFiles(fixture.workspaceRoot))
        }
    }

    @Test
    fun derivedIdentityOwnedByAnotherSessionIsNotReused() {
        Fixture().use { fixture ->
            val imported = fixture.importJpeg("normalized.jpg")
            val rawId = "art_${UUID.randomUUID()}"
            val rawPath = FileScopePath.fromModelReference(requireNotNull(imported.modelRef))
            val rawFile = fixture.resolve(rawPath.toModelReference())
            val rawHash = requireNotNull(imported.sha256)
            fixture.storage.artifacts.register(
                rawId,
                fixture.sessionId,
                rawPath.toModelReference(),
                "image/jpeg",
                imported.sizeBytes,
                rawHash,
                rawFile.toFile(),
            )
            val key = FileContentStore.sha256Hex("${fixture.sessionId}\n$rawId\n$rawHash".toByteArray())
            val foreignPath =
                FileScopePath(
                    rawPath.scopeId,
                    "input/attachment-derived/$key/image-existing/normalized.jpg",
                )
            val foreignFile = fixture.resolve(foreignPath.toModelReference())
            Files.createDirectories(foreignFile.parent)
            Files.write(foreignFile, fixture.jpegBytes)
            fixture.storage.sessions.create("foreign", "Synthetic foreign", null, null, 1)
            val foreign =
                fixture.storage.artifacts.register(
                    "art_normalized_$key",
                    "foreign",
                    foreignPath.toModelReference(),
                    "image/jpeg",
                    imported.sizeBytes,
                    rawHash,
                    foreignFile.toFile(),
                )
            val entry = requireNotNull(fixture.processor().restoreEntry(rawId, fixture.sessionId))
            assertNotNull(entry.imageSendError)
            assertNull(entry.normalizedArtifactId)
            assertEquals(foreign, fixture.storage.artifacts.resolve(foreign.id))
            assertArrayEquals(fixture.jpegBytes, Files.readAllBytes(foreignFile))
            assertRawPreserved(fixture, imported, entry)
        }
    }

    @Test
    fun historicalRawReferenceWithExifIsRejectedWithoutRewritingItsBinding() {
        Fixture().use { fixture ->
            val imported = fixture.importJpeg("normalized.jpg")
            val rawId = "historical-raw-${UUID.randomUUID()}"
            val rawFile = fixture.resolve(requireNotNull(imported.modelRef))
            val raw =
                fixture.storage.artifacts.register(
                    rawId,
                    fixture.sessionId,
                    requireNotNull(imported.modelRef),
                    "image/jpeg",
                    imported.sizeBytes,
                    requireNotNull(imported.sha256),
                    rawFile.toFile(),
                )
            val messageId = "historical-message"
            fixture.storage.messages.append(messageId, fixture.sessionId, null, "USER", "TEXT", "Historical attachment")
            fixture.storage.messageAttachments.bind(
                messageId,
                listOf(
                    MessageAttachmentRepository.Binding(rawId, AttachmentPurpose.REFERENCE, raw.sha256),
                ),
            )
            val binding = fixture.storage.messageAttachments.listByMessage(messageId)
            val image =
                ImageReference(
                    ArtifactRef(rawId),
                    "image/jpeg",
                    ImageBinding(fixture.sessionId, messageId, raw.sha256),
                )
            try {
                fixture.imageSource().load(image, fixture.provider)
                fail("historical raw EXIF must not reach the provider")
            } catch (expected: ImageReselectionRequiredException) {
                assertEquals(ModelErrorCode.IMAGE_RESELECT_REQUIRED.name, expected.message)
            }
            assertEquals(binding, fixture.storage.messageAttachments.listByMessage(messageId))
            assertEquals(raw, fixture.storage.artifacts.resolve(rawId))
            assertArrayEquals(fixture.jpegBytes, Files.readAllBytes(rawFile))
        }
    }

    @Test
    fun legitimateToolImageKeepsItsIndependentChecksAndIsNotSubjectToUserExifPolicy() {
        Fixture().use { fixture ->
            val local = fixture.installLocalProvider()
            val imported = fixture.importJpeg("tool.jpg")
            val file = fixture.resolve(requireNotNull(imported.modelRef))
            val artifact =
                fixture.storage.artifacts.register(
                    "tool-image",
                    fixture.sessionId,
                    requireNotNull(imported.modelRef),
                    "image/jpeg",
                    imported.sizeBytes,
                    requireNotNull(imported.sha256),
                    file.toFile(),
                )
            fixture.storage.turns.start("tool-turn", fixture.sessionId, 1)
            fixture.storage.messages.append(
                "tool-message",
                fixture.sessionId,
                "tool-turn",
                "TOOL",
                "TOOL_RESULT",
                "Observed image",
            )
            fixture.storage.messageAttachments.bind(
                "tool-message",
                listOf(
                    MessageAttachmentRepository.Binding(
                        artifact.id,
                        AttachmentPurpose.TOOL_OBSERVATION,
                        artifact.sha256,
                    ),
                ),
            )
            val image =
                ImageReference(
                    ArtifactRef(artifact.id),
                    "image/jpeg",
                    ImageBinding(fixture.sessionId, "tool-message", artifact.sha256, "tool-turn", local.model),
                )
            var allowed = true
            var checks = 0
            val source =
                fixture.imageSource { _, _ ->
                    checks++
                    allowed
                }
            assertArrayEquals(fixture.jpegBytes, Base64.getDecoder().decode(source.load(image, local).base64))
            assertEquals(2, checks)
            allowed = false
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { source.load(image, local) }
            assertArrayEquals(fixture.jpegBytes, Files.readAllBytes(file))
        }
    }

    private fun assertNormalized(
        fixture: Fixture,
        entry: StagedAttachmentEntry,
    ) {
        assertNull(entry.imageSendError)
        val normalizedId = requireNotNull(entry.normalizedArtifactId)
        val normalizedFile = requireNotNull(entry.normalizedFile)
        assertNotEquals(entry.artifactId, normalizedId)
        assertNotEquals(entry.file.toRealPath(), normalizedFile.toRealPath())
        val row = fixture.storage.artifacts.resolve(normalizedId)
        val path = FileScopePath.fromModelReference(row.relativePath)
        assertTrue(path.relativePath.startsWith("input/attachment-derived/"))
        assertEquals(entry.normalizedSha256, row.sha256)
        assertEquals(row.sha256, FileContentStore.sha256Hex(normalizedFile.toFile()))
        assertEquals(16, entry.normalizedWidth)
        assertEquals(32, entry.normalizedHeight)
        assertEquals("image/jpeg", entry.normalizedMediaType)
    }

    private fun assertRawPreserved(
        fixture: Fixture,
        imported: AttachmentImportResult,
        entry: StagedAttachmentEntry,
    ) {
        val row = fixture.storage.artifacts.resolve(entry.artifactId)
        assertEquals(imported.sha256, row.sha256)
        assertEquals(imported.sha256, entry.boundSha256)
        assertEquals(imported.modelRef, row.relativePath)
        assertEquals(imported.sizeBytes, row.size)
        assertEquals(imported.sha256, FileContentStore.sha256Hex(entry.file.toFile()))
        assertArrayEquals(fixture.jpegBytes, Files.readAllBytes(entry.file))
        val rawExif = ExifInterface(entry.file.toFile())
        assertEquals(PRIVATE_COMMENT, rawExif.getAttribute(ExifInterface.TAG_USER_COMMENT))
        assertNotNull(rawExif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNotNull(rawExif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE))
    }

    private fun assertProviderImageIsPrivate(
        fixture: Fixture,
        request: ImageReference,
    ) {
        val loaded = fixture.imageSource().load(request, fixture.provider)
        assertEquals("image/jpeg", loaded.mediaType)
        val bytes = Base64.getDecoder().decode(loaded.base64)
        val row = fixture.storage.artifacts.resolve(request.ref.value)
        assertArrayEquals(Files.readAllBytes(fixture.resolve(row.relativePath)), bytes)
        assertEquals(requireNotNull(request.binding).sha256, FileContentStore.sha256Hex(bytes))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("Exif\u0000\u0000"))
        val exif = bytes.inputStream().use { ExifInterface(it) }
        listOf(
            ExifInterface.TAG_USER_COMMENT,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
        ).forEach { tag -> assertNull("provider image must not contain $tag", exif.getAttribute(tag)) }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        assertEquals(16, bounds.outWidth)
        assertEquals(32, bounds.outHeight)
    }

    private fun regularFiles(root: Path): Set<Path> =
        Files.walk(root).use { paths ->
            paths
                .iterator()
                .asSequence()
                .filter { Files.isRegularFile(it) }
                .map { it.toRealPath() }
                .toSet()
        }

    private fun assertRejected(block: () -> Unit) {
        try {
            block()
            fail("unbound raw image must not be materialized")
        } catch (_: IllegalArgumentException) {
            // The production request-bound verifier must reject, rather than fall back to raw bytes.
        }
    }

    private class Fixture : AutoCloseable {
        var workersStopped = true
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val id = UUID.randomUUID().toString()
        private val root = File(context.cacheDir, "attachment-normalization-$id").apply { check(mkdirs()) }
        private val databaseName = "attachment-normalization-$id.db"
        private val content = File(root, "content")
        val sessionId = "session-$id"
        var storage = HelixStorage.open(context, databaseName, content)
            private set
        private val scope =
            FileScopePath.fromModelReference(
                requireNotNull(storage.sessions.create(sessionId, "Attachment fixture", null, null, 1L).directoryRef),
            )
        val workspaceRoot: Path get() = storage.workspaces.managedDirectory(scope.scopeId)
        private val roots = ScopeRootResolver { storage.workspaces.managedDirectory(it) }
        val jpegBytes = createPrivateJpeg(File(root, "source.jpg"))
        val provider =
            ProviderConfig(
                id = "fixture-provider",
                displayName = "Fixture provider",
                connection =
                    ProviderConnection(
                        ProviderProvisioningKind.ON_DEVICE_ASSET,
                        ProviderTransport.OnDeviceLocal,
                        ProviderAuth.None,
                    ),
                model = "fixture-model",
                headers = emptyMap(),
                capabilitySnapshot = "{}",
            )

        fun installLocalProvider(): ProviderConfig {
            val local = provider
            storage.providerConfigs.save(
                com.helix.core.storage.repository.ProviderConfigSpec(
                    local.id,
                    local.displayName,
                    null,
                    null,
                    local.model,
                    "{}",
                    null,
                    "{}",
                    provisioningKind = "ON_DEVICE_ASSET",
                    transportKind = "ON_DEVICE_LOCAL",
                    authKind = "NONE",
                ),
            )
            return local
        }

        fun resolve(modelRef: String): Path = resolveFileScopePath(FileScopePath.fromModelReference(modelRef), roots)

        fun importJpeg(name: String): AttachmentImportResult = importImage(name, "image/jpeg", jpegBytes)

        fun importImage(
            name: String,
            mediaType: String,
            bytes: ByteArray,
        ): AttachmentImportResult {
            val importer =
                AttachmentImporter(
                    SafImportPipeline(
                        roots,
                        SafSourceOpener { uri ->
                            check(uri == SOURCE_URI)
                            bytes.inputStream()
                        },
                    ),
                )
            return importer
                .importAttachment(
                    workspaceScopeId = scope.scopeId,
                    sourceUri = SOURCE_URI,
                    reported = SafSourceMetadata(bytes.size.toLong(), mediaType, name),
                    cancel = SafCancelToken { false },
                    sink = null,
                    sessionId = sessionId,
                ).also { assertEquals("fixture import must complete", ImportStatus.COMPLETED, it.status) }
        }

        fun prepare(imported: AttachmentImportResult): StagedAttachmentEntry {
            val result = processor().prepare(imported, sessionId)
            assertTrue("fixture must stage: $result", result is StagedAttachmentProcessor.Preparation.Ready)
            return (result as StagedAttachmentProcessor.Preparation.Ready).entry
        }

        fun processor(beforeResolve: (FileScopePath) -> Unit = {}): StagedAttachmentProcessor =
            StagedAttachmentProcessor(
                storage,
                staging(beforeResolve),
                { UUID.randomUUID().toString() },
                { resource, arguments -> context.getString(resource, *arguments) },
            )

        fun bind(entry: StagedAttachmentEntry): ImageReference {
            val artifactId = requireNotNull(entry.normalizedArtifactId)
            val hash = requireNotNull(entry.normalizedSha256)
            val messageId = "message-${UUID.randomUUID()}"
            storage.withTransaction {
                storage.messages.append(messageId, sessionId, null, "USER", "TEXT", "Fixture image")
                storage.messageAttachments.bind(
                    messageId,
                    listOf(MessageAttachmentRepository.Binding(artifactId, AttachmentPurpose.REFERENCE, hash)),
                )
            }
            return ImageReference(
                ArtifactRef(artifactId),
                requireNotNull(entry.normalizedMediaType),
                ImageBinding(sessionId, messageId, hash),
            )
        }

        fun imageSource(
            vision: (String, String) -> Boolean = { _, _ ->
                error("reference image must not request tool vision capability")
            },
        ): ArtifactVisionImageSource {
            val access =
                BoundImageAccess(
                    storage,
                    ToolVisionConsent(storage.interactionReceipts, SystemClock()),
                    vision,
                )
            return ArtifactVisionImageSource(storage.artifacts, WorkspaceArtifactStore(roots), access::verify)
        }

        fun reopen() {
            storage.close()
            storage = HelixStorage.open(context, databaseName, content)
        }

        private fun staging(beforeResolve: (FileScopePath) -> Unit = {}): AttachmentStagingSupport =
            AttachmentStagingSupport(
                importer =
                    AttachmentImporter(
                        SafImportPipeline(
                            roots,
                            SafSourceOpener { uri ->
                                check(uri == SOURCE_URI)
                                jpegBytes.inputStream()
                            },
                        ),
                    ),
                workspaceScopeId = scope.scopeId,
                sourceMetadata = { SafSourceMetadata(jpegBytes.size.toLong(), "image/jpeg", "source.jpg") },
                resolveWorkspacePath = { path ->
                    beforeResolve(path)
                    resolveFileScopePath(path, roots)
                },
            )

        override fun close() {
            // A failed termination check must not close or delete resources still used by a worker.
            if (!workersStopped) return
            try {
                storage.close()
            } finally {
                context.deleteDatabase(databaseName)
                root.deleteRecursively()
            }
        }
    }

    companion object {
        private const val SOURCE_URI = "content://attachment-normalization-fixture/image"
        private const val PRIVATE_COMMENT = "private-attachment-fixture-comment"

        private fun createBitmapImage(format: Bitmap.CompressFormat): ByteArray {
            val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xff336699.toInt())
                return ByteArrayOutputStream().use { output ->
                    assertTrue(bitmap.compress(format, 90, output))
                    output.toByteArray()
                }
            } finally {
                bitmap.recycle()
            }
        }

        private fun createPrivateJpeg(file: File): ByteArray {
            val bitmap = Bitmap.createBitmap(32, 16, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xff336699.toInt())
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
            } finally {
                bitmap.recycle()
            }
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                setAttribute(ExifInterface.TAG_USER_COMMENT, PRIVATE_COMMENT)
                setAttribute(ExifInterface.TAG_MAKE, "private-fixture-camera")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, "31/1,12/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "121/1,30/1,0/1")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
                saveAttributes()
            }
            return file.readBytes()
        }
    }
}
