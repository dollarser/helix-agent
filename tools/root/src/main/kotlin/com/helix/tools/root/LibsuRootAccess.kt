package com.helix.tools.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Production libsu adapter. Merely constructing it does not inspect or request Root. */
class LibsuRootAccess(
    context: Context,
) {
    private val controller = RootAccessController(LibsuRootAccessDriver(context.applicationContext))

    fun status(): RootAccessStatus = controller.status()

    fun onProfileChanged(isAdvanced: Boolean): RootAccessStatus = controller.onProfileChanged(isAdvanced)

    /** Must only be called from the explicit user-facing "Request Root" action. */
    fun requestRoot(): RootRequestStatus = controller.requestRoot()

    fun disconnect() = controller.disconnect()

    internal fun rootServiceProcessIdForTest(): Int? = controller.rootServiceProcessIdForTest()
}

private class LibsuRootAccessDriver(
    private val context: Context,
) : RootAccessDriver {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var connection: ServiceConnection? = null
    private var serviceBinder: IBinder? = null
    private var lossCallback: (() -> Unit)? = null
    private val requestGeneration = AtomicInteger()
    private val deathRecipient = IBinder.DeathRecipient(::notifyLost)

    override fun cachedGrant(): Boolean? = Shell.isAppGrantedRoot()

    override fun requestRoot(onResult: (RootRequestOutcome) -> Unit) {
        val generation = requestGeneration.incrementAndGet()
        Shell.EXECUTOR.execute {
            val outcome =
                try {
                    if (Shell.getShell().isRoot) {
                        RootRequestOutcome.GRANTED
                    } else {
                        RootRequestOutcome.DENIED
                    }
                } catch (_: RuntimeException) {
                    RootRequestOutcome.UNAVAILABLE
                }
            if (requestGeneration.get() != generation) {
                closeCachedShell()
            } else {
                mainHandler.post {
                    if (requestGeneration.get() == generation) {
                        onResult(outcome)
                    } else {
                        closeCachedShellAsync()
                    }
                }
            }
        }
    }

    override fun bindRootService(
        onConnected: (processId: Int) -> Unit,
        onLost: () -> Unit,
    ) {
        mainHandler.post {
            if (connection != null) return@post
            lossCallback = onLost
            val newConnection = rootServiceConnection(onConnected, onLost)
            connection = newConnection
            try {
                RootService.bind(Intent(context, HelixRootService::class.java), newConnection)
            } catch (_: RuntimeException) {
                clearConnection()
                onLost()
                return@post
            }
            mainHandler.postDelayed(
                {
                    if (connection === newConnection && serviceBinder == null) {
                        clearConnection()
                        onLost()
                    }
                },
                ROOT_SERVICE_BIND_TIMEOUT_MS,
            )
        }
    }

    override fun disconnect() {
        requestGeneration.incrementAndGet()
        mainHandler.post {
            clearConnection()
            closeCachedShellAsync()
        }
    }

    private fun closeCachedShellAsync() {
        Shell.EXECUTOR.execute(::closeCachedShell)
    }

    private fun closeCachedShell() {
        try {
            Shell.getCachedShell()?.close()
        } catch (_: IOException) {
            Unit
        }
    }

    private fun rootServiceConnection(
        onConnected: (processId: Int) -> Unit,
        onLost: () -> Unit,
    ): ServiceConnection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                binder: IBinder,
            ) {
                if (connection !== this) {
                    RootService.unbind(this)
                    closeCachedShellAsync()
                    return
                }
                serviceBinder = binder
                try {
                    binder.linkToDeath(deathRecipient, 0)
                    onConnected(readProcessId(binder))
                } catch (_: Exception) {
                    clearConnection()
                    onLost()
                }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (connection === this) notifyLost()
            }

            override fun onBindingDied(name: ComponentName) {
                if (connection === this) notifyLost()
            }

            override fun onNullBinding(name: ComponentName) {
                if (connection === this) notifyLost()
            }
        }

    private fun readProcessId(binder: IBinder): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(RootServiceProtocol.DESCRIPTOR)
            check(binder.transact(RootServiceProtocol.GET_PROCESS_ID, data, reply, 0))
            reply.readException()
            reply.readInt()
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun notifyLost() {
        val callback = lossCallback
        clearConnection()
        callback?.invoke()
    }

    private fun clearConnection() {
        val oldConnection = connection
        try {
            serviceBinder?.unlinkToDeath(deathRecipient, 0)
        } catch (_: NoSuchElementException) {
            Unit
        }
        connection = null
        serviceBinder = null
        lossCallback = null
        if (oldConnection != null) {
            try {
                RootService.unbind(oldConnection)
            } catch (_: IllegalStateException) {
                Unit
            }
        }
    }

    private companion object {
        const val ROOT_SERVICE_BIND_TIMEOUT_MS = 10_000L
    }
}
