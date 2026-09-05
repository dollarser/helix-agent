package com.helix.runtime.cli.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

sealed interface CliRuntimeVerification {
    data class Verified(val status: CliRuntimeStatus) : CliRuntimeVerification
    data class Unavailable(val cause: Cause) : CliRuntimeVerification

    enum class Cause { NOT_INSTALLED, DISABLED, FORCE_STOPPED, SIGNATURE_MISMATCH, BIND_REFUSED, TIMEOUT, HANDSHAKE_FAILED }
}

class CliRuntimeSupervisor(context: Context) {
    private val context = context.applicationContext

    fun verify(): CliRuntimeVerification {
        localCause()?.let { return CliRuntimeVerification.Unavailable(it) }
        val latch = CountDownLatch(1)
        var binder: IBinder? = null
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    binder = service
                    latch.countDown()
                }
                override fun onServiceDisconnected(name: ComponentName) { latch.countDown() }
                override fun onNullBinding(name: ComponentName) { latch.countDown() }
                override fun onBindingDied(name: ComponentName) { latch.countDown() }
            }
        val intent = Intent().setComponent(ComponentName(CliRuntimeProtocol.RUNTIME_PACKAGE, CliRuntimeProtocol.SERVICE_CLASS))
        val bound =
            try {
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            } catch (_: SecurityException) {
                return CliRuntimeVerification.Unavailable(CliRuntimeVerification.Cause.SIGNATURE_MISMATCH)
            } catch (_: RuntimeException) {
                return CliRuntimeVerification.Unavailable(CliRuntimeVerification.Cause.BIND_REFUSED)
            }
        if (!bound) return CliRuntimeVerification.Unavailable(CliRuntimeVerification.Cause.BIND_REFUSED)
        return try {
            val connected =
                try {
                    latch.await(CliRuntimeProtocol.BIND_DEADLINE_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    false
                }
            if (!connected) {
                CliRuntimeVerification.Unavailable(CliRuntimeVerification.Cause.TIMEOUT)
            } else {
                when (val outcome = binder?.let(CliStatusHandshakeClient::transact)) {
                    is CliStatusHandshakeClient.Outcome.Ok -> CliRuntimeVerification.Verified(outcome.status)
                    CliStatusHandshakeClient.Outcome.CallerMismatch ->
                        CliRuntimeVerification.Unavailable(CliRuntimeVerification.Cause.SIGNATURE_MISMATCH)
                    else -> CliRuntimeVerification.Unavailable(CliRuntimeVerification.Cause.HANDSHAKE_FAILED)
                }
            }
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    private fun localCause(): CliRuntimeVerification.Cause? {
        val info = packageInfo(CliRuntimeProtocol.RUNTIME_PACKAGE)
            ?: return CliRuntimeVerification.Cause.NOT_INSTALLED
        val app = info.applicationInfo ?: return CliRuntimeVerification.Cause.NOT_INSTALLED
        if (!app.enabled) return CliRuntimeVerification.Cause.DISABLED
        if (app.flags and ApplicationInfo.FLAG_STOPPED != 0) return CliRuntimeVerification.Cause.FORCE_STOPPED
        val own = signingDigests(packageInfo(context.packageName))
        val peer = signingDigests(info)
        if (own.isEmpty() || peer.none(own::contains)) return CliRuntimeVerification.Cause.SIGNATURE_MISMATCH
        return null
    }

    private fun packageInfo(packageName: String) =
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(
                    packageName,
                    PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun signingDigests(info: android.content.pm.PackageInfo?): Set<String> =
        info?.signingInfo?.apkContentsSigners.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { byte ->
                "%02x".format(byte)
            }
        }.toSet()
}
