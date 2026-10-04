package com.helix.app.automation.shizuku

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

internal data class ShizukuTapOutcome(
    val serverUid: Int,
    val userServiceUid: Int,
    val result: JsonObject,
)

internal class ShizukuUiBridge(
    private val context: Context,
) {
    private var cachedBinder: IBinder? = null
    private var cachedBinding: Pair<Shizuku.UserServiceArgs, ServiceConnection>? = null

    fun binderReady(): Boolean = runCatching { Shizuku.pingBinder() && Shizuku.getVersion() >= 13 }.getOrDefault(false)

    fun permissionGranted(): Boolean =
        binderReady() &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false)

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

    fun device(
        request: com.helix.tools.automation.AutomationDeviceRequest,
        allowed: () -> Boolean,
    ) = withService { binder, _ ->
        PrivilegedDeviceTransactions.execute(binder, request) { permissionGranted() && allowed() }
    }

    @Synchronized
    private fun <T> withService(block: (IBinder, Int) -> T): T {
        if (!permissionGranted()) {
            releaseBinding()
            error("SHIZUKU_PERMISSION_REQUIRED")
        }
        cachedBinder?.takeIf { it.isBinderAlive }?.let { binder ->
            val uid = PrivilegedUiTransactions.uid(binder)
            check(uid in setOf(0, 2000) && uid == serverUid()) { "SHIZUKU_IDENTITY_MISMATCH" }
            return block(binder, uid)
        }
        releaseBinding()
        val args =
            Shizuku
                .UserServiceArgs(
                    ComponentName(context.packageName, ShizukuUiUserService::class.java.name),
                ).daemon(false)
                .processNameSuffix("helix_ui")
                .tag("helix-mobile-use-ui")
                .version(10)
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
            block(binder, remoteUid)
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
