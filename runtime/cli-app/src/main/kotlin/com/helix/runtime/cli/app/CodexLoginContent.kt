package com.helix.runtime.cli.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

internal class CodexLoginContent(
    private val activity: Activity,
    onLogin: () -> Unit,
    onDeviceLogin: () -> Unit,
    onLogout: () -> Unit,
    onSmoke: () -> Unit,
    onCancel: () -> Unit,
    private val code: () -> String?,
    private val url: () -> String?,
) {
    private val deviceUserCode: String? get() = code()
    private val deviceVerificationUrl: String? get() = url()
    private val padding = (24 * activity.resources.displayMetrics.density).toInt()
    val root =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            addView(TextView(context).apply { setText(R.string.codex_login_warning) })
        }
    val status =
        TextView(activity).also {
            it.setPadding(0, padding, 0, padding)
            root.addView(it)
        }
    private val login = button(R.string.codex_login_action, onLogin)
    private val deviceLogin = button(R.string.codex_device_login_action, onDeviceLogin)
    private val openDeviceBrowser = button(R.string.codex_device_open_browser, ::openDeviceVerification)
    private val copyDeviceCode = button(R.string.codex_device_copy_code, ::copyCodeToClipboard)
    private val copyDeviceUrl = button(R.string.codex_device_copy_url, ::copyUrlToClipboard)
    private val logout = button(R.string.codex_logout_action, onLogout)
    private val smokeButton = button(R.string.codex_smoke_action, onSmoke)
    private val cancel = button(R.string.codex_login_cancel_action, onCancel)

    private fun button(
        label: Int,
        action: () -> Unit,
    ): Button =
        Button(activity).also {
            it.setText(label)
            it.setOnClickListener { action() }
            root.addView(it)
        }

    private fun openDeviceVerification() {
        val url = deviceVerificationUrl ?: return
        runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { status.setText(R.string.codex_login_browser_error) }
    }

    private fun copyCodeToClipboard() {
        val code = deviceUserCode ?: return
        DeviceCodeClipboard.copy(activity, activity.getString(R.string.codex_device_clip_code_label), code)
        status.text = activity.getString(R.string.codex_device_copied_code, code, deviceVerificationUrl)
    }

    private fun copyUrlToClipboard() {
        val url = deviceVerificationUrl ?: return
        DeviceCodeClipboard.copy(activity, activity.getString(R.string.codex_device_clip_url_label), url)
        status.text = activity.getString(R.string.codex_device_copied_url, deviceUserCode, url)
    }

    fun renderState(
        loggedIn: Boolean,
        busy: Boolean,
        deviceActive: Boolean,
    ) {
        status.setText(
            if (loggedIn) {
                R.string.codex_login_logged_in
            } else {
                R.string.codex_login_logged_out
            },
        )
        renderButtons(loggedIn, busy, deviceActive)
    }

    fun renderButtons(
        loggedIn: Boolean,
        busy: Boolean,
        deviceActive: Boolean,
    ) {
        login.isEnabled = !loggedIn && !busy
        deviceLogin.isEnabled = !loggedIn && !busy
        openDeviceBrowser.isEnabled = deviceActive && deviceVerificationUrl != null
        copyDeviceCode.isEnabled = deviceActive && deviceUserCode != null
        copyDeviceUrl.isEnabled = deviceActive && deviceVerificationUrl != null
        logout.isEnabled = loggedIn && !busy
        smokeButton.isEnabled = loggedIn && !busy
        cancel.isEnabled = busy
    }

    fun setBusy(
        busy: Boolean,
        loggedIn: Boolean,
    ) {
        login.isEnabled = !busy
        deviceLogin.isEnabled = !busy
        openDeviceBrowser.isEnabled = false
        copyDeviceCode.isEnabled = false
        copyDeviceUrl.isEnabled = false
        logout.isEnabled = !busy
        smokeButton.isEnabled = !busy && loggedIn
        cancel.isEnabled = busy
    }
}
