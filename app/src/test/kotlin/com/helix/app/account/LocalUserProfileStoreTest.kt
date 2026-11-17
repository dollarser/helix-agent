package com.helix.app.account

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Properties

class LocalUserProfileStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun nameIsLocalAndSurvivesStoreRecreation() =
        runBlocking {
            val directory = temporary.newFolder()
            val first = store(directory)
            first.saveName("  神火  ")
            val second = store(directory)
            second.restore()
            assertEquals("神火", second.state.value.displayName)
            second.reset()
            val third = store(directory)
            third.restore()
            assertNull(third.state.value.displayName)
        }

    @Test fun unicodeLimitCountsCodePointsAndRejectsMultiline() {
        val emoji = "\uD83D\uDE00"
        assertEquals(emoji.repeat(40), normalizedProfileName(emoji.repeat(40)))
        assertThrows(IllegalArgumentException::class.java) { normalizedProfileName(emoji.repeat(41)) }
        assertThrows(IllegalArgumentException::class.java) { normalizedProfileName("one\ntwo") }
        assertThrows(IllegalArgumentException::class.java) { normalizedProfileName("one\u2028two") }
        assertNull(normalizedProfileName("  "))
    }

    @Test fun failedNameValidationLeavesPreviouslySavedProfile() =
        runBlocking {
            val directory = temporary.newFolder()
            val store = store(directory)
            store.saveName("Before")
            try {
                store.saveName("x".repeat(41))
                error("Expected rejection")
            } catch (_: IllegalArgumentException) {
                assertEquals("Before", store.state.value.displayName)
                assertEquals("Before", LocalUserProfilePersistence(directory).read().displayName)
            }
        }

    @Test fun missingAvatarFallsBackAndPathCannotEscapePrivateDirectory() {
        val directory = temporary.newFolder()
        val outside = temporary.newFile("outside.png")
        val properties =
            Properties().apply {
                setProperty("name", "Name")
                setProperty("avatar", "../${outside.name}")
            }
        File(directory, "profile.properties").outputStream().use { properties.store(it, null) }
        val persistence = LocalUserProfilePersistence(directory)
        assertNull(persistence.read().avatarFile)
        assertEquals("Name", persistence.read().displayName)
    }

    @Test fun removingAvatarPreservesNameAndDeletesOldImage() =
        runBlocking {
            val directory = temporary.newFolder()
            val persistence = LocalUserProfilePersistence(directory)
            val avatar = persistence.writeAvatar(byteArrayOf(1, 2, 3))
            persistence.save(LocalUserProfile("Name", avatar))
            val store = store(directory)
            store.save("Name", removeAvatar = true)
            assertFalse(avatar.exists())
            assertEquals(LocalUserProfile("Name"), persistence.read())
        }

    @Test fun manifestWriteFailureDoesNotReplaceState() =
        runBlocking {
            val directory = temporary.newFolder()
            val store = store(directory)
            store.restore()
            assertTrue(File(directory, "profile.properties").mkdir())
            try {
                store.saveName("New")
                error("Expected failed atomic replacement")
            } catch (_: IOException) {
                assertEquals(LocalUserProfile(), store.state.value)
            }
        }

    @Test fun corruptManifestIsReportedAsReadFailure() {
        val directory = temporary.newFolder()
        val manifest = File(directory, "profile.properties")
        manifest.writeText("name=" + "x".repeat(41))
        assertThrows(IOException::class.java) { LocalUserProfilePersistence(directory).read() }
        manifest.writeText("name=\\uZZZZ")
        assertThrows(IOException::class.java) { LocalUserProfilePersistence(directory).read() }
    }

    private fun store(directory: File) =
        LocalUserProfileStore(LocalUserProfilePersistence(directory)) {
            error("No image requested")
        }
}
