package com.helix.app.localmodel

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.provider.api.local.ModelAssetRef
import com.helix.provider.api.local.ModelAssetStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.security.MessageDigest

/** Bounded synthetic pressure/reopen cycle, not physical full-disk or long-duration acceptance. */
class LocalModelStorageStressDeviceTest {
    @Test fun repeatedReopenCleanupAndLowSpaceNeverDeleteInstalledModel() =
        runBlocking {
            val root =
                Files
                    .createTempDirectory(
                        InstrumentationRegistry
                            .getInstrumentation()
                            .targetContext.cacheDir
                            .toPath(),
                        "model-storage-soak",
                    ).toFile()
            val model = byteArrayOf(71, 71, 85, 70, 3, 0, 0, 0, 1, 2, 3, 4)
            val hash = MessageDigest.getInstance("SHA-256").digest(model).joinToString("") { "%02x".format(it) }
            try {
                val models = root.resolve("models")
                val asset = ModelAssetStore(models).publish(hash, model.size.toLong(), model.inputStream())
                repeat(32) {
                    val store = ModelAssetStore(models)
                    val downloads = root.resolve("downloads")
                    val downloader = LocalModelDownloader(store, downloads, { 0L }) { error("No network expected") }
                    val publication = models.resolve("${"a".repeat(64)}.part").apply { writeBytes(ByteArray(65_536)) }
                    val transfer = downloads.resolve("${"b".repeat(64)}.part").apply { writeBytes(ByteArray(65_536)) }
                    val snapshot = downloader.storageSnapshot()
                    assertEquals(131_072L, snapshot.downloadBytes)
                    assertEquals(2, snapshot.downloadCount)
                    downloader.clearDownloads(snapshot)
                    assertTrue(!publication.exists() && !transfer.exists())
                    assertThrows(IllegalStateException::class.java) {
                        runBlocking { downloader.clearDownloads(snapshot) }
                    }
                    assertThrows(IllegalArgumentException::class.java) {
                        runBlocking {
                            downloader.download(
                                "https://invalid.example/model.gguf",
                                ModelAssetRef("c".repeat(64), "c".repeat(64), 12),
                                LocalModelDownloadPolicy(),
                            ) {}
                        }
                    }
                    assertEquals(model.toList(), store.verifiedFile(asset).readBytes().toList())
                    assertEquals(0, downloader.storageSnapshot().downloadCount)
                }
            } finally {
                root.deleteRecursively()
            }
        }
}
