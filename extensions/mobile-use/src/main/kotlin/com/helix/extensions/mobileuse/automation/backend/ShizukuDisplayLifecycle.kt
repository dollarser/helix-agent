package com.helix.extensions.mobileuse.automation.backend

import android.os.IBinder
import com.helix.extensions.mobileuse.automation.AutomationDisplayLifecycle
import com.helix.extensions.mobileuse.automation.AutomationDisplayPresentation

/** One original route. No resurrection; uncertainty may be refined only by an actual release confirmation. */
internal class ShizukuDisplayLifecycle(
    private val session: String,
    private val turn: String,
    private val generation: Long,
    private val publish: (AutomationDisplayPresentation) -> Unit,
    private val permittedPackage: (String) -> Boolean,
    private val finished: () -> Unit,
) {
    private var state = AutomationDisplayLifecycle.CREATING
    private var terminal = false
    private var observed: String? = null
    private var service: IBinder? = null
    private val death = IBinder.DeathRecipient { report(AutomationDisplayLifecycle.CONNECTION_LOST) }

    @Synchronized
    fun previewService(
        sessionId: String,
        turnId: String,
        expectedGeneration: Long,
    ): IBinder? =
        service?.takeIf {
            !terminal && state == AutomationDisplayLifecycle.ACTIVE &&
                session == sessionId && turn == turnId && generation == expectedGeneration && it.isBinderAlive
        }

    @Synchronized
    fun permitsPreviewPackage(packageName: String): Boolean =
        !terminal && state == AutomationDisplayLifecycle.ACTIVE && permittedPackage(packageName)

    @Synchronized
    fun discardIfNeverBound() {
        if (!terminal && service == null && state == AutomationDisplayLifecycle.CREATING) {
            report(AutomationDisplayLifecycle.UNAVAILABLE)
        }
    }

    @Synchronized
    fun bind(binder: IBinder) {
        if (terminal || service === binder) return
        check(service == null) { "DISPLAY_SERVICE_IDENTITY_CHANGED" }
        service = binder
        try {
            binder.linkToDeath(death, 0)
            publish(snapshot())
        } catch (_: android.os.RemoteException) {
            report(AutomationDisplayLifecycle.CONNECTION_LOST)
        }
    }

    @Synchronized
    fun report(next: AutomationDisplayLifecycle) {
        if (terminal) {
            if (next == AutomationDisplayLifecycle.RELEASED && state in
                setOf(
                    AutomationDisplayLifecycle.CONNECTION_LOST,
                    AutomationDisplayLifecycle.RELEASE_UNKNOWN,
                    AutomationDisplayLifecycle.UNKNOWN,
                )
            ) {
                state = next
                publish(snapshot())
            }
            return
        }
        state = next
        terminal = next !in setOf(AutomationDisplayLifecycle.CREATING, AutomationDisplayLifecycle.ACTIVE)
        if (terminal) {
            try {
                service?.unlinkToDeath(death, 0)
            } catch (_: NoSuchElementException) {
                // A death callback may already have consumed the registration.
            }
            service = null
        }
        try {
            publish(snapshot())
        } finally {
            if (terminal) finished()
        }
    }

    @Synchronized
    fun observedPackage(value: String?) {
        if (terminal || state != AutomationDisplayLifecycle.ACTIVE || value == null) return
        if (value.length > 256 || !value.matches(Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) ||
            !permittedPackage(value)
        ) {
            return
        }
        observed = value
        publish(snapshot())
    }

    @Synchronized
    fun refused(status: String) {
        if (!terminal && state == AutomationDisplayLifecycle.CREATING) {
            report(
                if (status in setOf("BACKGROUND_DISPLAY_UNAVAILABLE", "BACKGROUND_DISPLAY_STALE")) {
                    AutomationDisplayLifecycle.UNAVAILABLE
                } else {
                    AutomationDisplayLifecycle.UNKNOWN
                },
            )
        }
    }

    @Synchronized
    fun connectionFailed() {
        val neverConnected = service == null && state == AutomationDisplayLifecycle.CREATING
        report(
            when {
                service?.isBinderAlive == false -> AutomationDisplayLifecycle.CONNECTION_LOST
                neverConnected -> AutomationDisplayLifecycle.UNAVAILABLE
                else -> AutomationDisplayLifecycle.UNKNOWN
            },
        )
    }

    private fun snapshot() =
        AutomationDisplayPresentation(
            session,
            turn,
            state,
            generation,
            observed?.takeIf(permittedPackage),
        )
}
