package com.helix.app.localmodel

import com.helix.provider.api.local.ModelAssetRef
import com.helix.provider.api.local.ModelAssetStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.ArrayDeque

class LocalModelDownloaderTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun fresh200PublishesAndDuplicateVerifiedAssetSkipsNetwork() =
        runBlocking {
            val fixture = Fixture(modelBytes(128))
            fixture.enqueue(200, fixture.bytes)
            assertEquals(fixture.asset, fixture.download())
            assertEquals(1, fixture.connections)
            assertEquals(
                fixture.bytes.toList(),
                fixture.store
                    .verifiedFile(fixture.asset)
                    .readBytes()
                    .toList(),
            )
            assertEquals(fixture.asset, fixture.download())
            assertEquals("verified duplicate must not reconnect", 1, fixture.connections)
        }

    @Test fun a200ResponseToARangeRequestRestartsInsteadOfAppending() =
        runBlocking {
            val fixture = Fixture(modelBytes(160))
            fixture.partial().writeBytes(fixture.bytes.copyOfRange(0, 40))
            fixture.enqueue(200, fixture.bytes)
            fixture.download()
            assertEquals("bytes=40-", fixture.lastConnection().range)
            assertEquals(
                fixture.bytes.toList(),
                fixture.store
                    .verifiedFile(fixture.asset)
                    .readBytes()
                    .toList(),
            )
        }

    @Test fun cancellationRetainsPartialAndExact206ResumesIt() =
        runBlocking {
            val fixture = Fixture(modelBytes(196_608))
            fixture.enqueue(200, fixture.bytes)
            val cancelled =
                runCatching {
                    fixture.download { progress ->
                        if (
                            progress.phase == LocalModelTransferPhase.DOWNLOADING &&
                            progress.downloadedBytes >= 65_536
                        ) {
                            throw CancellationException("fixture pause")
                        }
                    }
                }.exceptionOrNull()
            assertTrue(cancelled is CancellationException)
            val offset = fixture.partial().length()
            assertTrue(offset in 1 until fixture.asset.sizeBytes)
            fixture.enqueue(
                206,
                fixture.bytes.copyOfRange(offset.toInt(), fixture.bytes.size),
                "Content-Range" to "bytes $offset-${fixture.bytes.lastIndex}/${fixture.bytes.size}",
            )
            fixture.download()
            assertEquals("bytes=$offset-", fixture.lastConnection().range)
            assertEquals(
                fixture.bytes.toList(),
                fixture.store
                    .verifiedFile(fixture.asset)
                    .readBytes()
                    .toList(),
            )
        }

    @Test fun mismatched206DeletesUnsafePartialAndFailsClosed() =
        runBlocking {
            val fixture = Fixture(modelBytes(256))
            fixture.partial().writeBytes(fixture.bytes.copyOfRange(0, 64))
            fixture.enqueue(
                206,
                fixture.bytes.copyOfRange(64, fixture.bytes.size),
                "Content-Range" to "bytes 63-255/256",
            )
            assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.download() } }
            assertFalse(fixture.partial().exists())
            assertTrue(fixture.store.list().isEmpty())
        }

    @Test fun digestMismatchNeverPublishesAndDiskFailureDoesNotOpenNetwork() =
        runBlocking {
            val bytes = modelBytes(128)
            val wrong = ModelAssetRef("f".repeat(64), "f".repeat(64), bytes.size.toLong())
            val fixture = Fixture(bytes, asset = wrong)
            fixture.enqueue(200, bytes)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.download() } }
            assertTrue(fixture.store.list().isEmpty())
            assertFalse(fixture.partial().exists())

            fixture.partial().writeBytes(bytes)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { fixture.download() } }
            assertFalse("a full but digest-invalid partial must not poison later retry", fixture.partial().exists())
            assertEquals("invalid full partial is rejected without a network request", 1, fixture.connections)

            val noSpace = Fixture(bytes, usableSpace = 0)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { noSpace.download() } }
            assertEquals(0, noSpace.connections)
        }

    @Test fun cleanupPreservesModelsAndUnknownFiles() =
        runBlocking {
            val fixture = Fixture(modelBytes(128))
            fixture.enqueue(200, fixture.bytes)
            fixture.download()
            fixture.partial().writeBytes(byteArrayOf(1, 2, 3))
            val unrelated = fixture.transfers.resolve("keep.txt").apply { writeText("keep") }
            val directory = fixture.transfers.resolve("${"a".repeat(64)}.part").apply { mkdir() }
            val link = fixture.transfers.resolve("${"b".repeat(64)}.part")
            java.nio.file.Files
                .createSymbolicLink(link.toPath(), unrelated.toPath())
            val snapshot = fixture.downloader.storageSnapshot()
            assertEquals(128L, snapshot.installedBytes)
            assertEquals(3L, snapshot.downloadBytes)
            assertEquals(1, snapshot.downloadCount)
            fixture.downloader.clearDownloads(snapshot)
            assertFalse(fixture.partial().exists())
            assertTrue(unrelated.exists())
            assertTrue(directory.isDirectory)
            assertTrue(
                java.nio.file.Files
                    .isSymbolicLink(link.toPath()),
            )
            val published = fixture.store.verifiedFile(fixture.asset).readBytes()
            assertEquals(fixture.bytes.toList(), published.toList())
            assertEquals(0, fixture.downloader.storageSnapshot().downloadCount)
        }

    @Test fun changedSnapshotCannotDeleteNewBytes() =
        runBlocking {
            val fixture = Fixture(modelBytes(128))
            fixture.partial().writeBytes(byteArrayOf(1))
            val old = fixture.downloader.storageSnapshot()
            fixture.partial().appendBytes(byteArrayOf(2))
            assertThrows(IllegalStateException::class.java) { runBlocking { fixture.downloader.clearDownloads(old) } }
            assertEquals(2L, fixture.partial().length())
            fixture.downloader.clearDownloads(fixture.downloader.storageSnapshot())
            assertFalse(fixture.partial().exists())
        }

    @Test fun busyCleanupFailsAndCancellationReleasesLock() =
        runBlocking {
            val fixture = Fixture(modelBytes(128))
            val before = fixture.downloader.storageSnapshot()
            fixture.enqueue(200, fixture.bytes)
            assertThrows(CancellationException::class.java) {
                runBlocking {
                    fixture.download {
                        assertThrows(IllegalStateException::class.java) {
                            runBlocking { fixture.downloader.clearDownloads(before) }
                        }
                        throw CancellationException("fixture pause")
                    }
                }
            }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { fixture.downloader.clearDownloads(before) }
            }
            fixture.downloader.clearDownloads(fixture.downloader.storageSnapshot())
            assertFalse(fixture.partial().exists())
        }

    @Test fun switchingModelPreservesPartialUntilConfirmed() =
        runBlocking {
            val fixture = Fixture(modelBytes(128))
            val old = fixture.transfers.resolve("${"a".repeat(64)}.part").apply { writeText("resume me") }
            val unknown = fixture.transfers.resolve("keep.txt").apply { writeText("keep") }
            assertThrows(IllegalStateException::class.java) { runBlocking { fixture.download() } }
            assertEquals("resume me", old.readText())
            assertEquals(0, fixture.connections)
            fixture.downloader.clearDownloads(fixture.downloader.storageSnapshot())
            fixture.enqueue(200, fixture.bytes)
            fixture.download()
            assertTrue(unknown.exists())
            assertEquals(1, fixture.store.list().size)
        }

    private inner class Fixture(
        val bytes: ByteArray,
        val asset: ModelAssetRef = asset(bytes),
        usableSpace: Long = Long.MAX_VALUE,
    ) {
        private val root = temp.newFolder("case-${System.nanoTime()}")
        val store = ModelAssetStore(root.resolve("models"))
        val transfers = root.resolve("transfers")
        private val queued = ArrayDeque<FakeConnection>()
        private val opened = mutableListOf<FakeConnection>()
        val downloader =
            LocalModelDownloader(store, transfers, { usableSpace }) { uri ->
                val connection = queued.removeFirst()
                connection.requestedUri = uri
                opened += connection
                connection
            }

        val connections: Int get() = opened.size

        fun partial() = transfers.resolve("${asset.sha256}.part")

        fun enqueue(
            status: Int,
            body: ByteArray,
            vararg headers: Pair<String, String>,
        ) {
            queued += FakeConnection(status, body, headers.toMap())
        }

        fun lastConnection(): FakeConnection = opened.last()

        suspend fun download(onProgress: (LocalModelTransferProgress) -> Unit = {}): ModelAssetRef =
            downloader.download("https://models.example/model.gguf", asset, LocalModelDownloadPolicy(), onProgress)
    }

    private class FakeConnection(
        private val status: Int,
        private val body: ByteArray,
        private val headers: Map<String, String>,
    ) : HttpURLConnection(URL("https://models.example/model.gguf")) {
        var range: String? = null
        var requestedUri: URI? = null

        override fun setRequestProperty(
            key: String,
            value: String,
        ) {
            if (key.equals("Range", ignoreCase = true)) range = value
        }

        override fun getResponseCode(): Int = status

        override fun getHeaderField(name: String): String? = headers[name]

        override fun getInputStream(): InputStream = ByteArrayInputStream(body)

        override fun connect() = Unit

        override fun disconnect() = Unit

        override fun usingProxy(): Boolean = false
    }

    private fun modelBytes(size: Int): ByteArray {
        require(size >= 8)
        return ByteArray(size).also {
            it[0] = 'G'.code.toByte()
            it[1] = 'G'.code.toByte()
            it[2] = 'U'.code.toByte()
            it[3] = 'F'.code.toByte()
            it[4] = 3
        }
    }

    private fun asset(bytes: ByteArray): ModelAssetRef {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return ModelAssetRef(hash, hash, bytes.size.toLong())
    }
}
