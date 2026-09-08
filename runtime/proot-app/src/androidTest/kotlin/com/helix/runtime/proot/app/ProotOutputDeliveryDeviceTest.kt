package com.helix.runtime.proot.app

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProotOutputDeliveryDeviceTest {
    @Test
    fun failedArchiveBuildNeverPublishesOrDeliversPartialBytes() =
        withDirectory { root ->
            val target = File(root, "output.zip")
            val delivered = File(root, "caller.zip")
            writable(delivered).use { output ->
                assertThrows(IOException::class.java) {
                    ProotOutputDelivery.persistAndDeliver(target, output) { partial ->
                        partial.writeText("incomplete archive")
                        throw IOException("fixture build failure")
                    }
                }
            }
            assertFalse(target.exists())
            assertFalse(File(root, "output.zip.pending").exists())
            assertEquals(0L, delivered.length())
        }

    @Test
    fun failedAtomicPublicationNeverDeliversUncommittedBytes() =
        withDirectory { root ->
            val target = File(root, "output.zip").apply { mkdir() }
            val existing = File(target, "owned-marker").apply { writeText("preserve") }
            val delivered = File(root, "caller.zip")
            writable(delivered).use { output ->
                assertThrows(IOException::class.java) {
                    ProotOutputDelivery.persistAndDeliver(target, output) { partial ->
                        partial.writeText("candidate archive")
                        "manifest"
                    }
                }
            }
            assertEquals("preserve", existing.readText())
            assertFalse(File(root, "output.zip.pending").exists())
            assertEquals(0L, delivered.length())
        }

    @Test
    fun brokenPipeAfterPartialTransferKeepsTheEntireDurableArchive() =
        withDirectory { root ->
            val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
            val pipe = ParcelFileDescriptor.createPipe()
            val reader = Executors.newSingleThreadExecutor()
            try {
                val received =
                    reader.submit<Int> {
                        ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { input -> input.read(ByteArray(4096)) }
                    }
                val target = File(root, "output.zip")
                val result =
                    pipe[1].use { output ->
                        ProotOutputDelivery.persistAndDeliver(target, output) { archive ->
                            archive.writeBytes(bytes)
                            "complete manifest"
                        }
                    }
                assertTrue(received.get(5, TimeUnit.SECONDS) in 1..4096)
                assertEquals("complete manifest", result.manifestDocument)
                assertFalse(result.delivered)
                assertArrayEquals(bytes, target.readBytes())
                assertTrue(File(root, "output-delivery-failed.txt").isFile)
                assertFalse(File(root, "output.zip.pending").exists())
            } finally {
                pipe.forEach { it.close() }
                reader.shutdownNow()
            }
        }

    private fun writable(file: File): ParcelFileDescriptor =
        ParcelFileDescriptor.open(
            file,
            ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY,
        )

    private fun withDirectory(action: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "delivery-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            action(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
