package com.helix.app.files

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
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
            context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    fun isWritable(): Boolean {
        if (Build.VERSION.SDK_INT >= 30) return isGranted()
        val writeGranted = context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        return isGranted() && writeGranted == PackageManager.PERMISSION_GRANTED
    }

    @Suppress("DEPRECATION")
    fun root(): Path {
        if (!isGranted()) throw ScopeNotAvailable("Shared storage permission is required")
        return Environment.getExternalStorageDirectory().toPath()
    }

    companion object {
        const val SCOPE_ID = "user-shared-storage"
    }
}
