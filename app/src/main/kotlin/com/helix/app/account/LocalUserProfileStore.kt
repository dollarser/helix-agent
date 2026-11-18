package com.helix.app.account

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** This profile belongs to the installation, including while signed out or switching cloud accounts. */
internal class LocalUserProfileStore internal constructor(
    private val persistence: LocalUserProfilePersistence,
    private val avatarImporter: suspend (Uri) -> ByteArray,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(LocalUserProfile())
    val state: StateFlow<LocalUserProfile> = mutableState.asStateFlow()
    private var restored = false

    suspend fun restore() = withContext(Dispatchers.IO) { mutex.withLock { restoreLocked() } }

    suspend fun save(
        avatarUri: Uri? = null,
        removeAvatar: Boolean = false,
    ) {
        require(avatarUri == null || !removeAvatar)
        withContext(Dispatchers.IO) {
            mutex.withLock {
                restoreLocked()
                val old = mutableState.value
                val bytes = avatarUri?.let { avatarImporter(it) }
                currentCoroutineContext().ensureActive()
                val replacement = bytes?.let(persistence::writeAvatar)
                val next = LocalUserProfile(replacement ?: old.avatarFile.takeUnless { removeAvatar })
                var committed = false
                try {
                    persistence.save(next)
                    committed = true
                } finally {
                    if (!committed) replacement?.delete()
                }
                mutableState.value = next
                if (old.avatarFile != next.avatarFile) old.avatarFile?.delete()
            }
        }
    }

    suspend fun reset() = save(removeAvatar = true)

    private fun restoreLocked() {
        if (!restored) {
            mutableState.value = persistence.read()
            restored = true
        }
    }

    companion object {
        @Volatile private var instance: LocalUserProfileStore? = null

        fun get(context: Context): LocalUserProfileStore =
            instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    val importer = LocalUserAvatarImporter(app.contentResolver)
                    LocalUserProfileStore(
                        LocalUserProfilePersistence(File(app.noBackupFilesDir, "local-user-profile")),
                        importer::import,
                    ).also { instance = it }
                }
            }
    }
}
