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

class LocalUserProfileStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun avatarSurvivesRecreationAndResetRemovesIt() =
        runBlocking {
            val directory = temporary.newFolder()
            val persistence = LocalUserProfilePersistence(directory)
            val avatar = persistence.writeAvatar(byteArrayOf(1, 2, 3))
            persistence.save(LocalUserProfile(avatar))
            val store = store(directory)
            store.restore()
            assertEquals(avatar, store.state.value.avatarFile)
            store.reset()
            assertFalse(avatar.exists())
            assertEquals(LocalUserProfile(), persistence.read())
        }

    @Test fun missingAvatarFallsBackAndPathCannotEscapePrivateDirectory() {
        val directory = temporary.newFolder()
        File(directory, "profile.properties").writeText("avatar=../outside.png")
        assertNull(LocalUserProfilePersistence(directory).read().avatarFile)
    }

    @Test fun manifestWriteFailureDoesNotReplaceState() =
        runBlocking {
            val directory = temporary.newFolder()
            val store = store(directory)
            store.restore()
            assertTrue(File(directory, "profile.properties").mkdir())
            val error = runCatching { store.save() }.exceptionOrNull()
            assertTrue(error is IOException)
            assertEquals(LocalUserProfile(), store.state.value)
        }

    @Test fun corruptManifestIsReportedAsReadFailure() {
        val directory = temporary.newFolder()
        File(directory, "profile.properties").writeText("avatar=\\uZZZZ")
        assertThrows(IOException::class.java) { LocalUserProfilePersistence(directory).read() }
    }

    private fun store(directory: File) =
        LocalUserProfileStore(LocalUserProfilePersistence(directory)) {
            error("No image requested")
        }
}
