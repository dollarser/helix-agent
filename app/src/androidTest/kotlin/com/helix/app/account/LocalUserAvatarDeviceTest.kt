package com.helix.app.account

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Isolated profile/image fixtures, never modifies the installation's real local profile or account. */
class LocalUserAvatarDeviceTest {
    @Test fun importsBoundedImageAndSurvivesSourceDeletionAndRecreation() =
        runBlocking {
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(app.cacheDir, "device-share/profile-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val source = File(root, "original.png")
                val bitmap = Bitmap.createBitmap(1024, 512, Bitmap.Config.ARGB_8888)
                try {
                    source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                } finally {
                    bitmap.recycle()
                }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", source)
                val persistence = LocalUserProfilePersistence(File(root, "profile"))
                val importer = LocalUserAvatarImporter(app.contentResolver)
                val store = LocalUserProfileStore(persistence, importer::import)
                store.save(uri)
                assertTrue(source.delete())
                val restored = LocalUserProfileStore(persistence, importer::import)
                restored.restore()
                assertEquals(store.state.value.avatarFile, restored.state.value.avatarFile)
                val image =
                    BitmapFactory.decodeFile(
                        restored.state.value.avatarFile!!
                            .absolutePath,
                    )
                assertNotNull(image)
                assertEquals(512, image.width)
                assertEquals(256, image.height)
                image.recycle()
                val oldAvatar = restored.state.value.avatarFile!!
                restored.save(removeAvatar = true)
                assertFalse(oldAvatar.exists())
            } finally {
                root.deleteRecursively()
            }
        }

    @Test fun invalidImageLeavesPreviousNameAndAvatarUnchanged() =
        runBlocking {
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(app.cacheDir, "device-share/profile-${UUID.randomUUID()}").apply { mkdirs() }
            try {
                val source = File(root, "not-image.txt").apply { writeText("not an image") }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", source)
                val persistence = LocalUserProfilePersistence(File(root, "profile"))
                val importer = LocalUserAvatarImporter(app.contentResolver)
                val store = LocalUserProfileStore(persistence, importer::import)
                store.save()
                val failure = runCatching { store.save(uri) }.exceptionOrNull()
                assertNotNull(failure)
                assertEquals(LocalUserProfile(), store.state.value)
                assertEquals(LocalUserProfile(), persistence.read())
            } finally {
                root.deleteRecursively()
            }
        }
}
