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
    data class Verified(
        val status: CliRuntimeStatus,
    ) : CliRuntimeVerification

    data class Unavailable(
        val cause: Cause,
    ) : CliRuntimeVerification

    enum class Cause {
        NOT_INSTALLED,
        DISABLED,
        FORCE_STOPPED,
        SIGNATURE_MISMATCH,
        BIND_REFUSED,
        TIMEOUT,
        HANDSHAKE_FAILED,
    }
}

sealed interface CliRuntimeConnection {
    class Opened internal constructor(
        val binder: IBinder,
        internal val connection: ServiceConnection,
    ) : CliRuntimeConnection

    data class Refused(
        val cause: CliRuntimeVerification.Cause,
    ) : CliRuntimeConnection
}

class CliRuntimeSupervisor(
    context: Context,
) {
    private val context = context.applicationContext

    fun verify(): CliRuntimeVerification {
        val opened = openConnection()
        if (opened is CliRuntimeConnection.Refused) return CliRuntimeVerification.Unavailable(opened.cause)
        opened as CliRuntimeConnection.Opened
        return try {
            when (val outcome = CliStatusHandshakeClient.transact(opened.binder)) {
                is CliStatusHandshakeClient.Outcome.Ok -> {
                    CliRuntimeVerification.Verified(outcome.status)
                }

                CliStatusHandshakeClient.Outcome.CallerMismatch -> {
                    CliRuntimeVerification.Unavailable(CliRuntimeVerification.Cause.SIGNATURE_MISMATCH)
                }

                else -> {
                    CliRuntimeVerification.Unavailable(CliRuntimeVerification.Cause.HANDSHAKE_FAILED)
                }
            }
        } finally {
            closeConnection(opened)
        }
    }

    /** Checks whether a user-triggered visible Runtime UI may be opened without binding it. */
    fun visibleUiCause(): CliRuntimeVerification.Cause? = localCause(checkStopped = false)

    fun openConnection(): CliRuntimeConnection {
        val cause = localCause()
        return if (cause != null) CliRuntimeConnection.Refused(cause) else bindConnection()
    }

    private fun bindConnection(): CliRuntimeConnection {
        val latch = CountDownLatch(1)
        var binder: IBinder? = null
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    binder = service
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    latch.countDown()
                }

                override fun onNullBinding(name: ComponentName) {
                    latch.countDown()
                }

                override fun onBindingDied(name: ComponentName) {
                    latch.countDown()
                }
            }
        val intent =
            Intent().setComponent(
                ComponentName(CliRuntimeProtocol.RUNTIME_PACKAGE, CliRuntimeProtocol.SERVICE_CLASS),
            )
        bindCause(intent, connection)?.let { return CliRuntimeConnection.Refused(it) }
        val connected =
            try {
                latch.await(CliRuntimeProtocol.BIND_DEADLINE_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
        val liveBinder = binder
        return if (!connected || liveBinder == null) {
            runCatching { context.unbindService(connection) }
            CliRuntimeConnection.Refused(
                if (!connected) CliRuntimeVerification.Cause.TIMEOUT else CliRuntimeVerification.Cause.HANDSHAKE_FAILED,
            )
        } else {
            CliRuntimeConnection.Opened(liveBinder, connection)
        }
    }

    private fun bindCause(
        intent: Intent,
        connection: ServiceConnection,
    ): CliRuntimeVerification.Cause? =
        try {
            if (context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                null
            } else {
                CliRuntimeVerification.Cause.BIND_REFUSED
            }
        } catch (_: SecurityException) {
            CliRuntimeVerification.Cause.SIGNATURE_MISMATCH
        } catch (_: RuntimeException) {
            CliRuntimeVerification.Cause.BIND_REFUSED
        }

    fun closeConnection(connection: CliRuntimeConnection.Opened) {
        runCatching { context.unbindService(connection.connection) }
    }

    private fun localCause(checkStopped: Boolean = true): CliRuntimeVerification.Cause? {
        val info =
            packageInfo(CliRuntimeProtocol.RUNTIME_PACKAGE)
                ?: return CliRuntimeVerification.Cause.NOT_INSTALLED
        val app = info.applicationInfo
        return when {
            app == null -> {
                CliRuntimeVerification.Cause.NOT_INSTALLED
            }

            !app.enabled -> {
                CliRuntimeVerification.Cause.DISABLED
            }

            checkStopped && app.flags and ApplicationInfo.FLAG_STOPPED != 0 -> {
                CliRuntimeVerification.Cause.FORCE_STOPPED
            }

            else -> {
                val own = signingDigests(packageInfo(context.packageName))
                val peer = signingDigests(info)
                if (own.isEmpty() || peer.none(own::contains)) {
                    CliRuntimeVerification.Cause.SIGNATURE_MISMATCH
                } else {
                    null
                }
            }
        }
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
        info
            ?.signingInfo
            ?.apkContentsSigners
            .orEmpty()
            .map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { byte ->
                    "%02x".format(byte)
                }
            }.toSet()
}
