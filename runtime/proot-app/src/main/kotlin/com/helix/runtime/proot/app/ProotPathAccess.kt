package com.helix.runtime.proot.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import java.io.File

/** Runtime-side OS boundary; selected scopes and user authorization remain independently checked in the host. */
internal object ProotPathAccess {
    fun verify(
        context: Context,
        directory: File,
    ) {
        val privateRoots = listOf("app", "managed").map { File(context.filesDir.canonicalFile, "workspaces/$it") }
        val external = context.getExternalFilesDirs(null).filterNotNull().map { it.canonicalFile }
        val shared =
            if (sharedGranted(context)) {
                val volumes =
                    if (Build.VERSION.SDK_INT >= 30) {
                        context
                            .getSystemService(android.os.storage.StorageManager::class.java)
                            .storageVolumes
                            .mapNotNull { it.directory?.canonicalFile }
                    } else {
                        emptyList()
                    }
                listOf(sharedRoot()) + volumes
            } else {
                emptyList()
            }
        require(
            (privateRoots + external + shared).any { root ->
                root.path == root.canonicalPath && directory.toPath().startsWith(root.toPath())
            },
        ) { "Workspace has no authorized direct filesystem mapping" }
        require(directory.path == directory.canonicalPath && directory.isDirectory)
        // A path string and canRead() alone do not prove this process can enumerate the directory.
        java.nio.file.Files
            .newDirectoryStream(directory.toPath())
            .use { it.iterator().hasNext() }
    }

    private fun sharedGranted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            val read = context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            val write = context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            read == PackageManager.PERMISSION_GRANTED && write == PackageManager.PERMISSION_GRANTED
        }

    @Suppress("DEPRECATION")
    private fun sharedRoot(): File = Environment.getExternalStorageDirectory().canonicalFile
}
