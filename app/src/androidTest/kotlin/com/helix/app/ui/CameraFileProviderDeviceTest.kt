package com.helix.app.ui

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CameraFileProviderDeviceTest {
    @Test
    fun cameraOutputUsesAppPrivateAttachmentDirectoryAndReadableContentUri() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "attachments/camera")
        assertTrue(directory.mkdirs() || directory.isDirectory)
        val file = File.createTempFile("helix-camera-test-", ".jpg", directory)
        try {
            file.writeBytes(byteArrayOf(0x01, 0x02, 0x03))
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            assertEquals("content", uri.scheme)
            assertEquals("${context.packageName}.fileprovider", uri.authority)
            context.contentResolver.openFileDescriptor(uri, "r").use { descriptor ->
                requireNotNull(descriptor)
                assertTrue(descriptor.statSize == 3L)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun copiedCameraSourceRetainsMismatchesAndDeletesOnlyVerifiedTransportCopy() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.filesDir, "attachments/camera")
        directory.mkdirs()
        val file = File.createTempFile("helix-camera-test-", ".jpg", directory)
        try {
            file.writeText("camera fixture")
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val cleanup =
                com.helix.app.infrastructure.chat
                    .CopiedCameraSource(context)
            val sha =
                com.helix.core.workspace.AtomicFileWriter
                    .sha256Hex(file.toPath())
            cleanup.release(uri.toString(), "0".repeat(64))
            assertTrue(file.isFile)
            cleanup.release(
                uri
                    .buildUpon()
                    .authority("other.provider")
                    .build()
                    .toString(),
                sha,
            )
            assertTrue(file.isFile)
            cleanup.release(uri.toString(), sha)
            org.junit.Assert.assertFalse(file.exists())
            cleanup.release(uri.toString(), sha)
        } finally {
            file.delete()
        }
    }
}
