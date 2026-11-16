package com.helix.app.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import com.helix.provider.api.wire.RequestDiagnostics
import java.io.File
import java.io.IOException
import java.util.UUID

/** Installation-scoped random ID excluded from Android backup and portable profiles. */
internal object ClientRequestDiagnostics {
    fun install(context: Context) {
        val fallback = UUID.randomUUID().toString()
        val installationId =
            try {
                storedId(context, fallback)
            } catch (_: IOException) {
                fallback
            } catch (_: SecurityException) {
                fallback
            }
        val version =
            try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
            } catch (_: PackageManager.NameNotFoundException) {
                "unknown"
            }
        RequestDiagnostics.configure("helix/$version", installationId)
    }

    private fun storedId(
        context: Context,
        fallback: String,
    ): String {
        val file = File(context.noBackupFilesDir, "request-diagnostic-installation-id")
        val id =
            if (file.exists()) {
                file.readText().trim().takeIf {
                    try {
                        UUID.fromString(it)
                        true
                    } catch (_: IllegalArgumentException) {
                        false
                    }
                }
            } else {
                null
            }
        return id ?: fallback.also { file.writeText(it) }
    }
}
