package com.helix.runtime.cli.app

import android.app.Activity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

internal class CopilotLoginContent(
    private val activity: Activity,
    onLogin: () -> Unit,
    onOpenBrowser: () -> Unit,
    onCancel: () -> Unit,
    onLogout: () -> Unit,
    onCopyCode: () -> Unit,
    onCopyUrl: () -> Unit,
) {
    private val padding = (24 * activity.resources.displayMetrics.density).toInt()
    val root = createRoot()
    val status =
        TextView(activity).also {
            it.setPadding(0, padding, 0, padding)
            it.setTextIsSelectable(true)
            root.addView(it)
        }
    val login = button(R.string.copilot_login_action, onLogin)
    val copyCode = button(R.string.copilot_copy_code, onCopyCode)
    val copyUrl = button(R.string.copilot_copy_url, onCopyUrl)
    val openBrowser = button(R.string.copilot_open_browser, onOpenBrowser)
    val cancel = button(R.string.copilot_cancel, onCancel)
    val logout = button(R.string.copilot_logout, onLogout)

    @Suppress("DEPRECATION") // minSdk 29 WindowInsets accessor keeps the disclosure clear of system chrome.
    private fun createRoot(): LinearLayout =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val attributes = activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
            val actionBarHeight = attributes.getDimensionPixelSize(0, 0)
            attributes.recycle()
            setOnApplyWindowInsetsListener { view, insets ->
                view.setPadding(
                    padding,
                    padding + actionBarHeight + insets.systemWindowInsetTop,
                    padding,
                    padding + insets.systemWindowInsetBottom,
                )
                insets
            }
            requestApplyInsets()
            addView(TextView(context).apply { setText(R.string.copilot_login_warning) })
        }

    private fun button(
        label: Int,
        action: () -> Unit,
    ): Button =
        Button(activity).also {
            it.setText(label)
            it.setOnClickListener { action() }
            root.addView(it)
        }
}
