package com.helix.app.files

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Environment
import androidx.core.content.PermissionChecker
import com.helix.core.workspace.ScopeNotAvailable
import java.nio.file.Path

/** User-operated file manager access only. Never installed in the Agent's scope resolver. */
class SharedStorageAccess(
    private val context: Context,
) {
    fun isGranted(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            PermissionChecker.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) ==
                PermissionChecker.PERMISSION_GRANTED
        }

    fun isWritable(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) return isGranted()
        val writeGranted = PermissionChecker.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return isGranted() && writeGranted == PermissionChecker.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    fun root(): Path {
        if (!isGranted()) throw ScopeNotAvailable("Shared storage permission is required")
        return Environment.getExternalStorageDirectory().toPath()
    }

    /** Platform-reported volumes, never inferred from a document URI. Permission is checked on every call. */
    internal fun directories(): List<SharedStorageDirectory> {
        if (!isGranted()) return emptyList()
        val primary = SharedStorageDirectory(SCOPE_ID, context.getString(com.helix.app.R.string.files_shared), root())
        val additional =
            if (Build.VERSION.SDK_INT >= 30) {
                val manager = context.getSystemService(android.os.storage.StorageManager::class.java)
                manager.storageVolumes.mapNotNull { volume ->
                    val directory = volume.directory?.canonicalFile ?: return@mapNotNull null
                    if (!directory.isDirectory || directory.toPath() == primary.path.toRealPath()) {
                        return@mapNotNull null
                    }
                    val hash =
                        java.security.MessageDigest
                            .getInstance("SHA-256")
                            .digest(directory.path.toByteArray())
                            .joinToString("") { "%02x".format(it) }
                    SharedStorageDirectory(VOLUME_PREFIX + hash, volume.getDescription(context), directory.toPath())
                }
            } else {
                emptyList()
            }
        return (listOf(primary) + additional).distinctBy { it.path }
    }

    fun resolveRoot(scopeId: String): Path =
        directories().singleOrNull { it.scopeId == scopeId }?.path
            ?: throw ScopeNotAvailable("Storage volume unavailable or permission revoked")

    companion object {
        const val SCOPE_ID = "user-shared-storage"
        private const val VOLUME_PREFIX = "user-shared-volume-"

        fun isScope(scopeId: String) = scopeId == SCOPE_ID || scopeId.startsWith(VOLUME_PREFIX)
    }
}

internal data class SharedStorageDirectory(
    val scopeId: String,
    val displayName: String,
    val path: Path,
)
