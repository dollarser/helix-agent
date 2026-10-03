package com.helix.extensions.mobileuse

import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.helix.tools.automation.HelixAccessibilityService

/** Two small trusted windows: the entire status surface passes touches through. Main-thread only. */
internal class MobileUseOverlayWindows(
    private val service: HelixAccessibilityService,
    takeOver: () -> Unit,
    returnToConversation: () -> Unit,
) {
    private val manager = service.getSystemService(WindowManager::class.java)
    private val status =
        TextView(service).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(10), dp(8), dp(10), dp(8))
            maxLines = 2
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background =
                GradientDrawable().apply {
                    setColor(0x55000000)
                    cornerRadius = dp(12).toFloat()
                }
        }
    private val controls = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private val takeOverButton =
        Button(service).apply {
            setText(R.string.mobile_overlay_take_over)
            setOnClickListener { takeOver() }
        }
    private val returnButton =
        Button(service).apply {
            setText(R.string.mobile_overlay_return)
            setOnClickListener { returnToConversation() }
        }
    private var added = false

    @Volatile private var windowIds: Set<Int> = emptySet()

    init {
        controls.addView(takeOverButton, LinearLayout.LayoutParams(dp(80), dp(48)))
        controls.addView(returnButton, LinearLayout.LayoutParams(dp(80), dp(48)))
    }

    // Remove a partially attached pair, then propagate the platform failure.
    @Suppress("TooGenericExceptionCaught")
    fun show(
        text: String,
        stopping: Boolean,
    ) {
        if (!added) {
            try {
                manager.addView(status, parameters(statusLayer = true))
                manager.addView(controls, parameters(statusLayer = false))
                added = true
            } catch (error: RuntimeException) {
                remove()
                throw error
            }
        }
        if (status.text.toString() != text) status.text = text
        takeOverButton.isEnabled = !stopping
        status.visibility = View.VISIBLE
        controls.visibility = View.VISIBLE
        windowIds = setOf(windowId(status), windowId(controls)).filter { it >= 0 }.toSet()
    }

    fun hide() {
        // Keep window identity while hidden; late events must not invalidate target observations.
        status.visibility = View.INVISIBLE
        controls.visibility = View.INVISIBLE
    }

    fun remove() {
        for (view in listOf(status, controls)) {
            if (view.isAttachedToWindow) manager.removeViewImmediate(view)
        }
        added = false
    }

    fun ownsWindow(id: Int): Boolean {
        // Attachment can assign IDs after show() returns. Resolve again on the service's
        // main-thread event callback, retaining hidden-window IDs for late events.
        if (added) {
            windowIds = windowIds + listOf(windowId(status), windowId(controls)).filter { it >= 0 }
        }
        return id >= 0 && id in windowIds
    }

    @Suppress("DEPRECATION")
    private fun windowId(view: View): Int {
        val node = view.createAccessibilityNodeInfo()
        return try {
            node.windowId
        } finally {
            node.recycle()
        }
    }

    internal fun parameters(statusLayer: Boolean): WindowManager.LayoutParams {
        val width = if (statusLayer) (service.resources.configuration.screenWidthDp - 112).coerceAtLeast(80) else 80
        return WindowManager
            .LayoutParams(
                dp(width),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    if (statusLayer) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or if (statusLayer) Gravity.START else Gravity.END
                x = dp(8)
                y = dp(48)
                setTitle(if (statusLayer) "Mobile Use status" else "Mobile Use controls")
            }
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()
}
