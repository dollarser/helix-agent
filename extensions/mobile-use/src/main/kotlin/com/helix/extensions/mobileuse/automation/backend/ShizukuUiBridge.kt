package com.helix.extensions.mobileuse.automation.backend

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.serialization.json.JsonObject
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class ShizukuBindingFailure(
    cause: Exception,
) : IllegalStateException("SHIZUKU_USER_SERVICE_UNAVAILABLE", cause)

data class ShizukuTapOutcome(
    val serverUid: Int,
    val userServiceUid: Int,
    val result: JsonObject,
)

class ShizukuUiBridge(
    private val context: Context,
) {
    private var cachedBinder: IBinder? = null
    private var cachedBinding: Pair<Shizuku.UserServiceArgs, ServiceConnection>? = null

    fun binderReady(): Boolean =
        com.helix.tools.deviceaccess.DeviceAccess
            .shizukuReady()

    fun permissionGranted(): Boolean =
        com.helix.tools.deviceaccess.DeviceAccess
            .shizukuGranted()

    fun serverUid(): Int = Shizuku.getUid()

    fun clickMatch(
        selector: ShizukuUiSelector,
        allowed: (Int, Int, Int) -> Boolean,
        mayFinish: () -> Boolean,
    ): ShizukuTapOutcome =
        withService { binder, remoteUid ->
            ShizukuTapOutcome(
                serverUid(),
                remoteUid,
                PrivilegedUiTransactions.click(
                    binder,
                    selector,
                    { x, y, rotation -> permissionGranted() && allowed(x, y, rotation) },
                    { permissionGranted() && mayFinish() },
                ),
            )
        }

    @Suppress("TooGenericExceptionCaught") // Preserve the original failure after projection.
    fun device(
        request: com.helix.extensions.mobileuse.automation.AutomationDeviceRequest,
        allowed: () -> Boolean,
    ): com.helix.extensions.mobileuse.automation.AutomationDeviceReply {
        val owner = request.background?.owner as? ShizukuExecutionGuard
        return try {
            withService { binder, _ ->
                owner?.bindDisplayService(binder)
                PrivilegedDeviceTransactions.execute(binder, request) { permissionGranted() && allowed() }.also {
                    if (it.target?.displayId?.let { id -> id > 0 } == true) {
                        owner?.observedDisplayPackage(it.target.packageName)
                    } else {
                        owner?.displayRefused(it.status)
                    }
                }
            }
        } catch (failure: Exception) {
            owner?.displayConnectionLost()
            throw failure
        }
    }

    @Synchronized
    private fun <T> withService(block: (IBinder, Int) -> T): T =
        executeAfterShizukuBinding(::resolveService) { block(it.first, it.second) }

    private fun resolveService(): Pair<IBinder, Int> {
        if (!permissionGranted()) {
            releaseBinding()
            error("SHIZUKU_PERMISSION_REQUIRED")
        }
        cachedBinder?.takeIf { it.isBinderAlive }?.let { binder ->
            val uid = PrivilegedUiTransactions.uid(binder)
            check(uid in setOf(0, 2000) && uid == serverUid()) { "SHIZUKU_IDENTITY_MISMATCH" }
            return binder to uid
        }
        releaseBinding()
        val args =
            Shizuku
                .UserServiceArgs(
                    ComponentName(context.packageName, ShizukuUiUserService::class.java.name),
                ).daemon(false)
                .processNameSuffix("helix_ui")
                .tag(installedServiceTag(context))
                .version(1)
                .debuggable(false)
        val connected = CountDownLatch(1)
        val remote = AtomicReference<IBinder?>()
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    service: IBinder?,
                ) {
                    remote.set(service)
                    connected.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    remote.set(null)
                }
            }
        var retained = false
        return try {
            Shizuku.bindUserService(args, connection)
            check(connected.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "SHIZUKU_USER_SERVICE_TIMEOUT" }
            val binder = checkNotNull(remote.get()) { "SHIZUKU_USER_SERVICE_MISSING" }
            check(permissionGranted() && binder.isBinderAlive) { "SHIZUKU_PERMISSION_OR_BINDER_LOST" }
            val remoteUid = PrivilegedUiTransactions.uid(binder)
            check(remoteUid in setOf(0, 2000) && remoteUid == serverUid()) { "SHIZUKU_IDENTITY_MISMATCH" }
            cachedBinder = binder
            cachedBinding = args to connection
            retained = true
            binder to remoteUid
        } finally {
            if (!retained) runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }

    private fun releaseBinding() {
        cachedBinder = null
        cachedBinding?.let { (args, connection) -> runCatching { Shizuku.unbindUserService(args, connection, true) } }
        cachedBinding = null
    }

    private companion object {
        const val BIND_TIMEOUT_SECONDS = 15L
    }
}

/** PackageManager replaces the APK location on installation. Never bind a prior install's bytecode. */
private fun installedServiceTag(context: Context): String {
    val installation =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(context.applicationInfo.sourceDir.toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it) }
    return "helix-mobile-use-ui-$installation"
}

/** The typed refusal covers resolution only; failures after execution starts retain their original truth. */
@Suppress("TooGenericExceptionCaught")
internal fun <B, T> executeAfterShizukuBinding(
    resolve: () -> B,
    execute: (B) -> T,
): T {
    val binding =
        try {
            resolve()
        } catch (failure: Exception) {
            throw ShizukuBindingFailure(failure)
        }
    return execute(binding)
}
