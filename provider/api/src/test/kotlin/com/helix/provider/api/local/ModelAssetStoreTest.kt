package com.helix.provider.api.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ModelAssetStoreTest {
    @get:Rule val directory = TemporaryFolder()
    private val bytes = byteArrayOf(71, 71, 85, 70, 3, 0, 0, 0, 1, 2, 3, 4)
    private val hash get() = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun abandonedPublicationCanBeClearedAfterReopenWithoutDeletingAssets() {
        val store = ModelAssetStore(directory.root)
        val asset = store.publish(hash, bytes.size.toLong(), bytes.inputStream())
        val abandoned = directory.root.resolve("${"a".repeat(64)}.part").apply { writeText("partial") }
        val unknown = directory.root.resolve("keep").apply { writeText("keep") }
        val linked = directory.root.resolve("${"b".repeat(64)}.part")
        java.nio.file.Files
            .createSymbolicLink(linked.toPath(), unknown.toPath())
        val reopened = ModelAssetStore(directory.root)
        val snapshot = reopened.publicationResidue()
        assertEquals(7L, snapshot.bytes)
        assertEquals(1, snapshot.count)
        reopened.clearPublicationResidue(snapshot)
        assertTrue(!abandoned.exists())
        assertTrue(unknown.exists())
        assertTrue(
            java.nio.file.Files
                .isSymbolicLink(linked.toPath()),
        )
        assertEquals(bytes.toList(), reopened.verifiedFile(asset).readBytes().toList())
    }

    @Test fun staleAndForeignPublicationConfirmationsCannotClearNewData() {
        val store = ModelAssetStore(directory.root)
        val partial = directory.root.resolve("$hash.part").apply { writeText("old") }
        val snapshot = store.publicationResidue()
        partial.appendText("new")
        assertThrows(IllegalStateException::class.java) { store.clearPublicationResidue(snapshot) }
        assertEquals("oldnew", partial.readText())
        val refreshed = store.publicationResidue()
        assertThrows(IllegalStateException::class.java) {
            ModelAssetStore(directory.root).clearPublicationResidue(refreshed)
        }
        store.clearPublicationResidue(refreshed)
        assertTrue(!partial.exists())
    }

    @Test fun publicationInProgressCannotBeClearedEvenFromReentrantCallback() {
        val store = ModelAssetStore(directory.root)
        store.publish(hash, bytes.size.toLong(), bytes.inputStream()) {
            val snapshot = store.publicationResidue()
            assertThrows(IllegalStateException::class.java) { store.clearPublicationResidue(snapshot) }
        }
        assertEquals(1, store.list().size)
    }

    @Test
    fun verifiedPublishReopenTamperAndDelete() {
        val store = ModelAssetStore(directory.root)
        val asset = store.publish(hash, bytes.size.toLong(), bytes.inputStream())
        assertEquals(listOf(asset), ModelAssetStore(directory.root).list())
        val file = store.verifiedFile(asset)
        file.writeBytes(bytes + 0)
        assertThrows(IllegalArgumentException::class.java) { store.verifiedFile(asset) }
        store.delete(asset)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun invalidOrInterruptedTransfersAreNeverPublished() {
        val store = ModelAssetStore(directory.root)
        assertThrows(IllegalArgumentException::class.java) { store.publish("f".repeat(64), 12, bytes.inputStream()) }
        assertThrows(IllegalArgumentException::class.java) { store.publish(hash, 11, bytes.inputStream()) }
        assertThrows(
            IllegalStateException::class.java,
        ) { store.publish(hash, 12, bytes.inputStream()) { error("cancel") } }
        assertTrue(
            directory.root
                .listFiles()
                .orEmpty()
                .isEmpty(),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ModelAssetStore(directory.root, 8).publish(hash, 12, bytes.inputStream())
        }
    }

    @Test
    fun quotaPreflightMatchesPublishAdmissionWithoutCreatingAFile() {
        val store = ModelAssetStore(directory.root, quotaBytes = bytes.size.toLong())
        val asset = ModelAssetRef(hash, hash, bytes.size.toLong())
        store.requireCanPublish(asset)
        assertTrue(store.list().isEmpty())
        store.publish(hash, bytes.size.toLong(), bytes.inputStream())
        store.requireCanPublish(asset)
        val otherHash = "a".repeat(64)
        assertThrows(IllegalArgumentException::class.java) {
            store.requireCanPublish(ModelAssetRef(otherHash, otherHash, 1))
        }
    }

    @Test
    fun symlinksAndNonGgufAreRejected() {
        val file = directory.newFile("outside")
        java.nio.file.Files
            .createSymbolicLink(directory.root.toPath().resolve("$hash.part"), file.toPath())
        val store = ModelAssetStore(directory.root)
        assertThrows(IllegalArgumentException::class.java) { store.publish(hash, 12, bytes.inputStream()) }
        assertEquals(0, file.length())
        val bad = "not-a-gguf".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bad).joinToString("") { "%02x".format(it) }
        assertThrows(
            IllegalArgumentException::class.java,
        ) { store.publish(digest, bad.size.toLong(), bad.inputStream()) }
    }

    @Test
    fun cancellationDuringFinalReadCannotPublish() {
        val store = ModelAssetStore(directory.root)
        var cancelled = false
        val source =
            object : java.io.ByteArrayInputStream(bytes) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int = super.read(buffer, offset, length).also { if (it == -1) cancelled = true }
            }
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            store.publish(hash, bytes.size.toLong(), source) {
                if (cancelled) throw java.util.concurrent.CancellationException("cancel before publication")
            }
        }
        assertTrue(ModelAssetStore(directory.root).list().isEmpty())
        assertEquals(0, store.publicationResidue().count)
    }

    @Test
    fun readFailureDuringReplacementPreservesPublishedAsset() {
        val store = ModelAssetStore(directory.root)
        val asset = store.publish(hash, bytes.size.toLong(), bytes.inputStream())
        val source =
            object : java.io.ByteArrayInputStream(bytes) {
                override fun read(
                    buffer: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (available() == 0) throw java.io.IOException("injected source failure")
                    return super.read(buffer, offset, length)
                }
            }
        assertThrows(java.io.IOException::class.java) {
            store.publish(hash, bytes.size.toLong(), source)
        }
        assertEquals(bytes.toList(), ModelAssetStore(directory.root).verifiedFile(asset).readBytes().toList())
        assertEquals(0, store.publicationResidue().count)
    }
}
