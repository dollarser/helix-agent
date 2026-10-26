package com.helix.app.knowledge

import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.util.UUID

/** Feasibility only: exact UUID synthetic rows, no product directory switch or permission request. */
class DocumentsMediaStoreDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver get() = context.contentResolver

    @Test fun ownDocumentsFilesSupportCrudRequeryAndFileDescriptorWithoutStorageGrants() {
        check(context.packageName.endsWith(".asrvalidation"))
        check(Build.VERSION.SDK_INT >= 29)
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val directory = "Documents/Helix/library/feasibility-${UUID.randomUUID()}/"
        val permissions =
            listOf(
                "android.permission.READ_EXTERNAL_STORAGE",
                "android.permission.WRITE_EXTERNAL_STORAGE",
                "android.permission.READ_MEDIA_IMAGES",
                "android.permission.MANAGE_EXTERNAL_STORAGE",
            )
        val before = permissions.map(context::checkSelfPermission)
        assertFalse(allFilesGranted())
        val created = mutableListOf<android.net.Uri>()

        fun insert(
            name: String,
            mime: String,
        ): android.net.Uri {
            val values =
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, directory)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            return requireNotNull(resolver.insert(collection, values)).also(created::add)
        }
        try {
            val note = insert("note.md", "text/markdown")
            val original = insert("original.bin", "application/octet-stream")
            val text = "# Synthetic Documents note\nFirst body".toByteArray()
            val binary = byteArrayOf(0, 1, 2, 127, -1)
            listOf(note to text, original to binary).forEach { (uri, bytes) ->
                publish(uri, bytes)
            }
            assertEquals(setOf(ContentUris.parseId(note), ContentUris.parseId(original)), publishedIds(directory))
            val updated = "# Synthetic Documents note\nUpdated body".toByteArray()
            resolver.openOutputStream(note, "rwt")!!.use { it.write(updated) }
            assertArrayEquals(updated, resolver.openInputStream(note)!!.use { it.readBytes() })
            val descriptor = resolver.openFileDescriptor(original, "r")
            assertNotNull(descriptor)
            ParcelFileDescriptor.AutoCloseInputStream(requireNotNull(descriptor)).use {
                assertArrayEquals(binary, it.readBytes())
            }
            assertEquals(before, permissions.map(context::checkSelfPermission))
            assertFalse(allFilesGranted())
            val write = context.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE")
            assertEquals(PackageManager.PERMISSION_DENIED, write)
        } finally {
            created.forEach { check(resolver.delete(it, null, null) == 1) }
        }
        resolver
            .query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                arrayOf(directory),
                null,
            )!!
            .use {
                assertEquals(0, it.count)
            }
    }

    private fun publish(
        uri: android.net.Uri,
        bytes: ByteArray,
    ) {
        resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
        assertArrayEquals(bytes, resolver.openInputStream(uri)!!.use { it.readBytes() })
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        assertEquals(1, resolver.update(uri, values, null, null))
    }

    private fun publishedIds(directory: String): Set<Long> {
        val columns =
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.OWNER_PACKAGE_NAME,
            )
        val selection =
            listOf(
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                "${MediaStore.MediaColumns.IS_PENDING} = 0",
            ).joinToString(" AND ")
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val args = arrayOf(directory, context.packageName)
        return resolver.query(collection, columns, selection, args, null)!!.use { cursor ->
            buildSet {
                while (cursor.moveToNext()) {
                    assertEquals(directory, cursor.getString(1))
                    assertEquals(context.packageName, cursor.getString(2))
                    add(cursor.getLong(0))
                }
            }
        }
    }

    private fun allFilesGranted() = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
}
