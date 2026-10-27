package com.helix.app.maintenance

import android.content.Context
import android.provider.Settings
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.PreviewDatabaseResetRequired
import java.security.MessageDigest

@android.annotation.SuppressLint("UseKtx") // Reset safety requires checking synchronous commit success.
internal fun previewStateGate(context: Context): PreviewPrivateStateGate {
    val mobile =
        com.helix.app.automation.AutomationModule
            .previewConfiguration(context)
    val witness = context.getSharedPreferences("preview-reset-boot", Context.MODE_PRIVATE)
    val boots =
        PreviewBootWitness(
            { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 } },
            { key -> witness.getInt(key, -1).takeIf { it >= 0 } },
            { key, boot -> check(witness.edit().putInt(key, boot).commit()) },
        )
    return PreviewPrivateStateGate(
        context.filesDir,
        databaseReset = {
            try {
                HelixStorage.requireCurrentPreview(context)
                null
            } catch (required: PreviewDatabaseResetRequired) {
                required
            }
        },
        readMobile = { mobile?.read?.invoke().orEmpty() },
        validateMobile = { mobile?.valid?.invoke() ?: true },
        clearMobile = { requireNotNull(mobile).clear() },
        rebootProvesStopped = { fingerprint ->
            val key =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(fingerprint.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            boots.provesStopped(key)
        },
        runtimeActive = {
            val manager = context.getSystemService(android.app.ActivityManager::class.java)
            requireNotNull(manager.runningAppProcesses) { "Runtime process state unavailable" }
                .any { it.processName.startsWith(context.packageName + ":") }
        },
    )
}
