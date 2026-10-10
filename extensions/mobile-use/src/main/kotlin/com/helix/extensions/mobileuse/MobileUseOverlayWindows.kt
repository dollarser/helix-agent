package com.helix.extensions.mobileuse

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.helix.extensions.plugin.PluginTaskApproval

/** Two small trusted windows: the entire status surface passes touches through. Main-thread only. */
internal class MobileUseOverlayWindows(
    private val service: Context,
    takeOver: () -> Unit,
    returnToConversation: () -> Unit,
    private val review: (String, Boolean) -> Unit,
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
            setText(R.string.mobile_overlay_view_chat)
            setOnClickListener { returnToConversation() }
        }
    private var approval: PluginTaskApproval? = null
    private var submitted: String? = null
    private var expanded = false
    private val approvalTitle =
        TextView(service).apply {
            textSize = 16f
            setTextColor(Color.WHITE)
            maxLines = 2
        }
    private val approvalSummary =
        TextView(service).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            maxLines = 3
        }
    private val approvalDetails =
        TextView(service).apply {
            textSize = 13f
            setTextColor(Color.WHITE)
        }
    private val approvalContent = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
    private val contentScroll =
        object : ScrollView(service) {
            override fun onMeasure(
                widthMeasureSpec: Int,
                heightMeasureSpec: Int,
            ) {
                val limit = dp((service.resources.configuration.screenHeightDp - 280).coerceIn(48, 240))
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST))
            }
        }.apply { addView(approvalContent) }
    private val expandButton =
        Button(service).apply {
            setText(R.string.mobile_overlay_details)
            setOnClickListener {
                expanded = !expanded
                approvalDetails.visibility = if (expanded) View.VISIBLE else View.GONE
                setText(if (expanded) R.string.mobile_overlay_collapse else R.string.mobile_overlay_details)
            }
        }
    private val approveButton =
        Button(service).apply {
            setText(R.string.mobile_overlay_approve)
            isAllCaps = false
            setTextColor(Color.WHITE)
            backgroundTintList =
                android.content.res.ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf()),
                    intArrayOf(0xff2367c9.toInt(), 0xff566071.toInt()),
                )
            setOnClickListener { decide(true) }
        }
    private val denyButton =
        Button(service).apply {
            setText(R.string.mobile_overlay_deny)
            isAllCaps = false
            setOnClickListener { decide(false) }
        }
    private val approvalPanel =
        LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background =
                GradientDrawable().apply {
                    setColor(0xff202124.toInt())
                    cornerRadius = dp(12).toFloat()
                }
        }
    private var added = false

    @Volatile private var windowIds: Set<Int> = emptySet()

    init {
        approvalContent.addView(approvalTitle)
        approvalContent.addView(approvalSummary)
        approvalContent.addView(expandButton, LinearLayout.LayoutParams(-1, dp(48)))
        approvalContent.addView(approvalDetails)
        approvalPanel.addView(contentScroll, LinearLayout.LayoutParams(-1, -2))
        val actions = LinearLayout(service)
        actions.addView(denyButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(approveButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        approvalPanel.addView(actions)
        controls.addView(approvalPanel, LinearLayout.LayoutParams(-1, -2))
        controls.addView(takeOverButton, LinearLayout.LayoutParams(-1, dp(48)))
        controls.addView(returnButton, LinearLayout.LayoutParams(-1, dp(48)))
    }

    // Remove a partially attached pair, then propagate the platform failure.
    @Suppress("TooGenericExceptionCaught")
    fun show(
        text: String,
        stopping: Boolean,
        pending: List<PluginTaskApproval> = emptyList(),
    ) {
        if (presentApproval(pending, stopping) &&
            added
        ) {
            manager.updateViewLayout(controls, parameters(statusLayer = false))
        }
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

    private fun presentApproval(
        pending: List<PluginTaskApproval>,
        stopping: Boolean,
    ): Boolean {
        val next = pending.firstOrNull()
        val resize = (approval != null) != (next != null)
        if (approval?.id != next?.id) {
            submitted = null
            expanded = false
            expandButton.setText(R.string.mobile_overlay_details)
            contentScroll.scrollTo(0, 0)
        }
        approval = next
        approvalPanel.visibility = if (next == null) View.GONE else View.VISIBLE
        approvalDetails.visibility = if (expanded && next != null) View.VISIBLE else View.GONE
        updateText(
            approvalTitle,
            next
                ?.let {
                    service.getString(R.string.mobile_overlay_request, pending.size, it.title)
                }.orEmpty(),
        )
        updateText(approvalSummary, next?.summary.orEmpty())
        updateText(approvalDetails, next?.let { it.summary + "\n" + it.details }.orEmpty())
        approveButton.isEnabled = !stopping && next != null && submitted != next.id
        denyButton.isEnabled = approveButton.isEnabled
        returnButton.setText(R.string.mobile_overlay_view_chat)
        return resize
    }

    private fun updateText(
        view: TextView,
        value: String,
    ) {
        if (view.text.toString() != value) view.text = value
    }

    private fun decide(approve: Boolean) {
        val current = approval ?: return
        if (submitted == current.id || !approveButton.isEnabled) return
        submitted = current.id
        approveButton.isEnabled = false
        denyButton.isEnabled = false
        review(current.id, approve)
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
        val width =
            if (statusLayer) {
                (service.resources.configuration.screenWidthDp - 112).coerceAtLeast(80)
            } else if (approval !=
                null
            ) {
                (service.resources.configuration.screenWidthDp - 16).coerceIn(160, 320)
            } else {
                80
            }
        return WindowManager
            .LayoutParams(
                dp(width),
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (service is AccessibilityService) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    if (statusLayer) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0,
                PixelFormat.TRANSLUCENT,
            ).apply {
                // Android 12+ treats application overlays as untrusted touch occluders.
                if (statusLayer && service !is AccessibilityService) alpha = 0.6f
                gravity = Gravity.TOP or if (statusLayer) Gravity.START else Gravity.END
                x = dp(8)
                y = dp(if (approval != null && !statusLayer) 96 else 48)
                setTitle(if (statusLayer) "Mobile Use status" else "Mobile Use controls")
            }
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density).toInt()
}
