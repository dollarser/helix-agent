package com.helix.extensions.mobileuse

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import com.helix.core.model.TurnState
import com.helix.extensions.mobileuse.automation.AutomationRuntimePresentation
import com.helix.extensions.plugin.PluginTaskHost
import com.helix.extensions.plugin.PluginTaskIdentity
import com.helix.tools.framework.ExecutableToolCall
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Plugin-owned surface. The host remains the only owner of task state and cancellation. */
@Suppress("TooManyFunctions") // Presentation lifecycle plus task controls implement one shared surface.
internal class MobileUseOverlay(
    private val service: Context,
    private val host: PluginTaskHost,
    private val surface: () -> Context? = { service },
    private val enabled: () -> Boolean,
) : AutomationRuntimePresentation {
    private val handler = Handler(Looper.getMainLooper())
    private val state = MobileUseOverlayState(restoreDelayMillis = RESTORE_DELAY_MILLIS)
    private var windowContext: Context? = null

    @Volatile private var windows: MobileUseOverlayWindows? = null
    private var displayed: PluginTaskIdentity? = null
    private var closed = false
    private val tick = Runnable { render() }

    fun showApproval(task: PluginTaskIdentity) {
        if (state.bind(task)) handler.post { render() }
    }

    override fun bind(call: ExecutableToolCall): Boolean {
        val conversation = call.sessionId
        val turn = call.turnId
        if (conversation == null || turn == null) return true
        val accepted = state.bind(PluginTaskIdentity(conversation, turn))
        if (accepted) handler.post { render() }
        return accepted
    }

    override fun stopBoundTask(task: PluginTaskIdentity) {
        if (state.takeOver(task)) host.requestStop(task)
        handler.post { render() }
    }

    override fun executionAllowed(): Boolean = state.allowed()

    override fun ownsWindow(windowId: Int): Boolean = windows?.ownsWindow(windowId) == true

    @Suppress("TooGenericExceptionCaught") // Platform window failures must not terminate the tool service.
    private fun render() {
        handler.removeCallbacks(tick)
        if (closed) return
        val owner = state.owner
        val snapshot = owner?.let(host::snapshot)
        try {
            val available =
                enabled() && !service.getSystemService(android.app.KeyguardManager::class.java).isDeviceLocked
            val source = if (available) surface() else null
            if (source !== windowContext) {
                check(detach()) { "Overlay detach failed" }
                windows =
                    source?.let { MobileUseOverlayWindows(it, ::takeOver, ::returnToConversation, ::reviewApproval) }
                windowContext = source
            }
            val visible = owner != null && source != null && state.visible(owner, snapshot, host.conversationVisible)
            if (visible &&
                !com.helix.extensions.mobileuse.automation.AutomationServiceController.physicalInput
                    .isOccupied()
            ) {
                displayed = owner
                val label = overlayLabel(state.allowed(), snapshot)
                windows?.show(service.getString(label), !state.allowed(), snapshot?.approvals.orEmpty())
            } else {
                displayed = null
                detach()
            }
        } catch (error: RuntimeException) {
            // The existing foreground notification remains available if Android refuses this UI.
            Log.w("MobileUseOverlay", "Presentation unavailable: ${error.javaClass.simpleName}")
            detach()
        }
        if (owner != null && snapshot?.active == true) handler.postDelayed(tick, 500)
    }

    private fun takeOver() {
        val owner = displayed ?: return
        if (state.takeOver(owner)) {
            host.requestStop(owner)
            render()
        }
    }

    private fun returnToConversation() {
        val owner = displayed ?: return
        if (!state.canNavigate(owner)) return
        // Navigation does not change the original execution identity, approval or cancellation.
        host.openConversation(owner)
        render()
    }

    private fun reviewApproval(
        approvalId: String,
        approve: Boolean,
    ) {
        val owner = displayed?.takeIf { state.owner == it && state.allowed() } ?: return
        if (state.canReview(owner, host.snapshot(owner), approvalId)) {
            host.reviewApproval(owner, approvalId, approve)
            render()
        }
    }

    override fun hideForOperation(call: ExecutableToolCall): AutoCloseable? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Physical operations cannot block the UI thread" }
        val ticket = state.suppress()
        val released = AtomicBoolean()
        val release =
            AutoCloseable {
                if (released.compareAndSet(false, true)) {
                    state.release(ticket)
                    handler.postDelayed({ render() }, RESTORE_DELAY_MILLIS)
                }
            }
        val hidden = CountDownLatch(1)
        val confirmed = AtomicBoolean()
        handler.post {
            if (state.reuseHiddenConfirmation() && windows?.attached != true) {
                confirmed.set(true)
                hidden.countDown()
                return@post
            }
            if (windows == null) {
                confirmed.set(state.confirmHidden(ticket))
                hidden.countDown()
                return@post
            }
            if (!detach()) {
                hidden.countDown()
                return@post
            }
            // Security-sensitive system buttons reject touches while any Helix overlay window remains attached.
            // Wait two frame boundaries so WindowManager/InputDispatcher observe the removal before capture/gesture.
            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback {
                    confirmed.set(windows?.attached != true && state.confirmHidden(ticket))
                    hidden.countDown()
                }
            }
        }
        return try {
            val removed = hidden.await(500, TimeUnit.MILLISECONDS) && confirmed.get()
            val active = !call.cancel.isCancelled() && Instant.now().isBefore(call.deadline)
            if (removed && active) {
                release
            } else {
                release.close()
                null
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            release.close()
            null
        }
    }

    override fun hide() {
        state.hide()
        handler.post {
            if (state.owner == null) {
                handler.removeCallbacks(tick)
                detach()
            } else {
                render()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // WindowManager failures must refuse the operation, not report hidden.
    private fun detach(): Boolean =
        try {
            windows?.remove()
            true
        } catch (error: RuntimeException) {
            Log.w("MobileUseOverlay", "Window removal unavailable: ${error.javaClass.simpleName}")
            false
        }

    override fun refreshSurface() {
        handler.post { render() }
    }

    override fun close() {
        state.close()
        val removed = CountDownLatch(1)
        val error =
            java.util.concurrent.atomic
                .AtomicReference<RuntimeException?>()
        val remove =
            Runnable {
                closed = true
                handler.removeCallbacks(tick)
                if (!detach()) error.set(IllegalStateException("MOBILE_USE_WINDOW_CLEANUP_FAILED"))
                removed.countDown()
            }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            remove.run()
        } else {
            check(handler.post(remove)) { "MOBILE_USE_WINDOW_CLEANUP_REJECTED" }
            try {
                check(removed.await(500, TimeUnit.MILLISECONDS)) { "MOBILE_USE_WINDOW_CLEANUP_TIMED_OUT" }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("MOBILE_USE_WINDOW_CLEANUP_INTERRUPTED", interrupted)
            }
        }
        error.get()?.let { throw it }
    }
}

private const val RESTORE_DELAY_MILLIS = 150L

private fun overlayLabel(
    allowed: Boolean,
    snapshot: com.helix.extensions.plugin.PluginTaskSnapshot?,
): Int =
    when {
        !allowed || snapshot?.state == TurnState.CANCELLING -> R.string.mobile_overlay_stopping
        snapshot?.awaitingApproval == true -> R.string.mobile_overlay_approval
        snapshot?.awaitingDataDisclosure == true -> R.string.mobile_overlay_image_confirmation
        snapshot?.state in setOf(TurnState.WAITING_MODEL, TurnState.RECEIVING_MODEL) -> R.string.mobile_overlay_thinking
        else -> R.string.mobile_overlay_working
    }
