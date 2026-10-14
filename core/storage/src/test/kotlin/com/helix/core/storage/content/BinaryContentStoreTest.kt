package com.helix.core.storage.content

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BinaryContentStoreTest {
    private fun withStore(block: (FileContentStore) -> Unit) {
        val root = Files.createTempDirectory("document-content").toFile()
        try {
            block(FileContentStore(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun binaryBytesAreHashedExactlyAndSurviveAStoreRestart() =
        withStore { store ->
            val bytes = byteArrayOf(0, -1, -128, 12, 45)
            val ref = store.writeBinary(bytes.inputStream(), 100) { }
            assertEquals(FileContentStore.sha256Hex(bytes), ref.sha256)
            assertArrayEquals(bytes, FileContentStore(store.root).verifiedBinary(ref, 100) { }.readBytes())
            assertEquals(ref, store.writeBinary(bytes.inputStream(), 100) { })
        }

    @Test fun overflowNeverPublishesAReferenceAndRemovesStaging() =
        withStore { store ->
            assertThrows(
                IllegalArgumentException::class.java,
            ) { store.writeBinary(ByteArray(8193).inputStream(), 8192) { } }
            assertTrue(store.root.walkTopDown().none { it.isFile })
        }

    @Test fun cancellationDuringCopyCleansStagingAndDoesNotReturnAHash() =
        withStore { store ->
            var polls = 0
            assertThrows(IllegalStateException::class.java) {
                store.writeBinary(ByteArray(8193).inputStream(), 10000) { if (++polls > 1) error("cancelled") }
            }
            assertTrue(store.root.walkTopDown().none { it.isFile })
        }

    @Test fun missingTamperedAndOversizedSnapshotsAreRejected() =
        withStore { store ->
            val ref = store.writeBinary(byteArrayOf(1, 2).inputStream(), 100) { }
            assertThrows(IllegalArgumentException::class.java) { store.verifiedBinary(ref, 1) { } }
            store.verifiedBinary(ref, 100) { }.writeBytes(byteArrayOf(3, 4))
            assertThrows(IllegalArgumentException::class.java) { store.verifiedBinary(ref, 100) { } }
            store.delete(ref)
            assertThrows(IllegalArgumentException::class.java) { store.verifiedBinary(ref, 100) { } }
        }

    @Test fun gcProtectsReferencedBinaryAndReclaimsUnreferencedContent() =
        withStore { store ->
            val ref = store.writeBinary(byteArrayOf(1, 2).inputStream(), 100) { }
            assertEquals(
                0,
                StorageGarbageCollector
                    .collectGarbage(store.root, {
                        it == ref.toStorageString()
                    }, 0)
                    .deletedContentFiles,
            )
            assertEquals(1, StorageGarbageCollector.collectGarbage(store.root, { false }, 0).deletedContentFiles)
        }
}
