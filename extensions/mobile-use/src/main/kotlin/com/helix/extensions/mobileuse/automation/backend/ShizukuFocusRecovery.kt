package com.helix.extensions.mobileuse.automation.backend

import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock

internal fun focusCleanupRefusal(
    remainingMillis: Long,
    authorized: Boolean,
    locked: Boolean,
    displayPresent: Boolean,
): String? =
    when {
        remainingMillis !in 0..ShizukuFocusRecovery.RECOVERY_MILLIS -> "CLEANUP_EXPIRED"
        !authorized -> "AUTHORIZATION_LOST"
        locked -> "DEVICE_LOCKED"
        displayPresent -> "ORIGINAL_DISPLAY_STILL_PRESENT"
        else -> null
    }

/** Cleanup evidence from the original shell display owner, never model-supplied task identifiers. */
internal data class FocusRecoveryCheckpoint(
    val display: Int,
    val primary: Int,
    val owned: List<Int>,
) {
    init {
        require(display > 0 && primary > 0 && owned.size in 1..32)
        require(owned.all { it > 0 && it != primary } && owned.distinct().size == owned.size)
    }

    fun write(parcel: Parcel) {
        parcel.writeInt(display)
        parcel.writeInt(primary)
        parcel.writeInt(owned.size)
        owned.forEach(parcel::writeInt)
    }

    /** A changed main-screen task order is a refusal, not permission to restore a stale focus. */
    fun canRestore(
        currentOrder: List<Int>,
        focused: Int?,
    ): Boolean = focused in owned && currentOrder.firstOrNull { it !in owned } == primary

    companion object {
        fun read(parcel: Parcel): FocusRecoveryCheckpoint {
            val display = parcel.readInt()
            val primary = parcel.readInt()
            val count = parcel.readInt().also { require(it in 1..32) }
            return FocusRecoveryCheckpoint(display, primary, List(count) { parcel.readInt() })
        }
    }
}

/** Original resource cleanup. UI lifecycle projection never drives this effect. */
internal class ShizukuFocusRecovery(
    private val enqueue: (() -> Unit) -> Unit = { work -> workers.execute(work) },
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
) {
    private var service: IBinder? = null
    private var checkpoint: FocusRecoveryCheckpoint? = null
    private var recovery: ((FocusRecoveryCheckpoint, Long) -> Unit)? = null
    private var terminal = false
    private val death = IBinder.DeathRecipient { died() }

    @Synchronized
    fun bind(
        binder: IBinder,
        restore: (FocusRecoveryCheckpoint, Long) -> Unit,
    ) {
        if (terminal || service === binder) return
        check(service == null) { "FOCUS_RECOVERY_OWNER_CHANGED" }
        service = binder
        recovery = restore
        try {
            binder.linkToDeath(death, 0)
        } catch (_: android.os.RemoteException) {
            died()
        }
    }

    @Synchronized
    fun record(value: FocusRecoveryCheckpoint) {
        if (!terminal && service?.isBinderAlive == true) checkpoint = value
    }

    @Synchronized
    fun released() {
        terminal = true
        checkpoint = null
        recovery = null
        try {
            service?.unlinkToDeath(death, 0)
        } catch (_: NoSuchElementException) {
            // The actual death may already have consumed the registration.
        }
        service = null
    }

    private fun died() {
        val work =
            synchronized(this) {
                if (terminal) return
                terminal = true
                val saved = checkpoint
                val restore = recovery
                checkpoint = null
                recovery = null
                service = null
                if (saved == null || restore == null) null else Triple(saved, restore, elapsed() + RECOVERY_MILLIS)
            } ?: return
        try {
            enqueue { work.second(work.first, work.third) }
        } catch (full: java.util.concurrent.RejectedExecutionException) {
            android.util.Log.w("HelixDisplay", "Focus cleanup queue full", full)
        }
    }

    companion object {
        const val RECOVERY_MILLIS = 5000L
        private val workers =
            java.util.concurrent.ThreadPoolExecutor(
                1,
                1,
                0,
                java.util.concurrent.TimeUnit.SECONDS,
                java.util.concurrent.ArrayBlockingQueue(8),
                { work -> Thread(work, "helix-display-cleanup").apply { isDaemon = true } },
            )
    }
}
