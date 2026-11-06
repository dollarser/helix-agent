package com.helix.extensions.mobileuse.automation.backend

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.helix.extensions.mobileuse.automation.AutomationServiceController
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The app owns the system display token; helper death only loses pixels and observations. */
internal class HostDisplayLease(
    context: Context,
    private val allowed: () -> Boolean,
    private val released: () -> Unit,
) {
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val token = Binder().apply { attachInterface(null, "android.hardware.display.IVirtualDisplayCallback") }
    private var control: IBinder? = null
    private var releaseCode = 0
    private var identity = UUID.randomUUID().toString()
    private var displayId = -1
    private var tasks = emptyList<RetainedDisplayTask>()

    @Volatile private var closed = false
    val isClosed: Boolean get() = closed
    private var worker: java.util.concurrent.ScheduledExecutorService? = null

    @Synchronized
    fun acquire(
        system: IBinder,
        release: Int,
    ): RetainedDisplayLease {
        check(!closed && allowed()) { "BACKGROUND_DISPLAY_UNAVAILABLE" }
        require(system.interfaceDescriptor == DISPLAY_DESCRIPTOR && release > 0)
        control = system
        releaseCode = release
        val existing = displays.getDisplay(displayId)
        if (existing == null || existing.name != "Helix background $identity") {
            displayId = -1
            identity = UUID.randomUUID().toString()
        }
        if (worker == null) {
            worker =
                Executors
                    .newSingleThreadScheduledExecutor { work ->
                        Thread(work, "helix-display-lease").apply { isDaemon = true }
                    }.also {
                        it.scheduleWithFixedDelay({ expire() }, 1, 1, TimeUnit.SECONDS)
                    }
        }
        return RetainedDisplayLease(token, identity, displayId, tasks)
    }

    @Synchronized
    fun bound(
        id: Int,
        resource: String,
    ) {
        check(!closed && allowed() && resource == identity && id > 0)
        displayId = id
    }

    @Synchronized
    fun checkpoint(value: List<RetainedDisplayTask>) {
        if (!closed && allowed()) {
            tasks = (value + tasks).distinctBy { it.id }.take(32)
        }
    }

    /** Cleanup runs after any physical input prefix exits, including when no helper is alive. */
    @Suppress("TooGenericExceptionCaught") // Failed release is logged and retried; it is never claimed as success.
    private fun expire() {
        if (permitted()) return
        AutomationServiceController.withPhysicalOperation {
            synchronized(this) {
                if (closed || permitted()) return@withPhysicalOperation
                try {
                    val data = Parcel.obtain()
                    val reply = Parcel.obtain()
                    try {
                        data.writeInterfaceToken(DISPLAY_DESCRIPTOR)
                        data.writeStrongBinder(token)
                        check(requireNotNull(control).transact(releaseCode, data, reply, 0))
                        reply.readException()
                    } finally {
                        data.recycle()
                        reply.recycle()
                    }
                    closed = true
                    tasks = emptyList()
                    worker?.shutdown()
                    released()
                } catch (failure: Exception) {
                    android.util.Log.w("HelixDisplay", "Host display release unconfirmed", failure)
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Unreadable authorization cannot keep a control resource alive.
    private fun permitted(): Boolean =
        try {
            allowed()
        } catch (failure: Exception) {
            android.util.Log.w("HelixDisplay", "Display authorization unreadable", failure)
            false
        }

    companion object {
        const val DISPLAY_DESCRIPTOR = "android.hardware.display.IDisplayManager"
    }
}

internal data class RetainedDisplayTask(
    val id: Int,
    val packageName: String,
)

internal data class RetainedDisplayLease(
    val token: IBinder,
    val identity: String,
    val displayId: Int,
    val tasks: List<RetainedDisplayTask>,
)
