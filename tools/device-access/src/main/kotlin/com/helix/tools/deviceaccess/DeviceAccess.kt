package com.helix.tools.deviceaccess

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.helix.tools.root.HelixRootService
import com.helix.tools.root.LibsuRootAccess

/** Helix owns OS grants. Trusted consumers own distinct service connections and operation policy. */
@Suppress("TooManyFunctions") // Host authorization and per-consumer connection lifecycle share one owner.
object DeviceAccess {
    private val roots = mutableMapOf<String, LibsuRootAccess>()
    private val enabledConsumers = mutableMapOf<String, () -> Boolean>()

    @Synchronized
    fun configure(
        context: Context,
        consumer: String,
        service: Class<out HelixRootService>,
        enabled: () -> Boolean = { false },
    ) {
        require(consumer.isNotBlank())
        roots.getOrPut(consumer) { LibsuRootAccess(context.applicationContext, service) }
        enabledConsumers[consumer] = enabled
    }

    @Synchronized
    fun root(consumer: String): LibsuRootAccess? = roots[consumer]

    /** Only the host's explicit user authorization action invokes this, never capability discovery. */
    fun requestRootFromUser(consumer: String) = root(consumer)?.requestAuthorization()

    /** Reuse an existing authorized shell; never launch su or an authorization prompt. */
    fun connectRoot(consumer: String) = checkNotNull(root(consumer)).connectAuthorized()

    /** Called once after explicit host authorization, never by discovery or passive status polling. */
    @Synchronized
    fun connectEnabledRootConsumers() {
        enabledConsumers.filterValues { it() }.keys.forEach { connectRoot(it) }
    }

    fun onAppBackgrounded(consumer: String) = root(consumer)?.onAppBackgrounded()

    /** Release one consumer's transport, not the app-wide grant or another consumer's connection. */
    fun disconnectRoot(consumer: String) = root(consumer)?.disconnect()

    fun shizukuReady(): Boolean =
        runCatching {
            rikka.shizuku.Shizuku.pingBinder() &&
                rikka.shizuku.Shizuku.getVersion() >= 13
        }.getOrDefault(false)

    fun shizukuGranted(): Boolean =
        shizukuReady() &&
            runCatching {
                rikka.shizuku.Shizuku.checkSelfPermission() ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun accessibilityEnabled(
        context: Context,
        component: ComponentName,
    ): Boolean =
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1 &&
            Settings.Secure
                .getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty()
                .split(':')
                .mapNotNull(ComponentName::unflattenFromString)
                .any { it == component }
}
