package com.helix.runtime.cli.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
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
    private val clientVersion = CodexClientVersionSection(activity)
    private val browserHint = hint(R.string.codex_browser_method_hint)
    private val login = button(R.string.codex_login_action, onLogin)
    private val deviceHint = hint(R.string.codex_device_method_hint)
    private val deviceLogin = button(R.string.codex_device_login_action, onDeviceLogin)
    private val openDeviceBrowser = button(R.string.codex_device_open_browser, ::openDeviceVerification)
    private val copyDeviceCode = button(R.string.codex_device_copy_code, ::copyCodeToClipboard)
    private val copyDeviceUrl = button(R.string.codex_device_copy_url, ::copyUrlToClipboard)
    private val logout = button(R.string.codex_logout_action, onLogout)
    private val smokeButton = button(R.string.codex_smoke_action, onSmoke)
    private val cancel = button(R.string.codex_login_cancel_action, onCancel)

    init {
        button(R.string.codex_advanced_options) {
            clientVersion.view.visibility = if (clientVersion.view.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        clientVersion.view.visibility = View.GONE
        root.addView(clientVersion.view)
    }

    private fun hint(label: Int): TextView =
        TextView(activity).also {
            it.setText(label)
            it.setPadding(0, padding / 2, 0, 0)
            root.addView(it)
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

    private fun openDeviceVerification() {
        val url = deviceVerificationUrl ?: return
        val code = deviceUserCode ?: return
        DeviceCodeClipboard.copy(activity, activity.getString(R.string.codex_device_clip_code_label), code)
        status.text = activity.getString(R.string.codex_device_copied_code, code, url)
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
        listOf(browserHint, deviceHint, login, deviceLogin).forEach {
            it.visibility = if (loggedIn) View.GONE else View.VISIBLE
        }
        listOf(openDeviceBrowser, copyDeviceCode, copyDeviceUrl).forEach {
            it.visibility = if (deviceActive) View.VISIBLE else View.GONE
        }
        listOf(logout, smokeButton).forEach {
            it.visibility = if (loggedIn) View.VISIBLE else View.GONE
        }
        cancel.visibility = if (busy) View.VISIBLE else View.GONE
        val canLogin = !loggedIn && !busy
        val canUseAccount = loggedIn && !busy
        login.isEnabled = canLogin
        clientVersion.setEnabled(!busy)
        deviceLogin.isEnabled = canLogin
        openDeviceBrowser.isEnabled = deviceActive && deviceVerificationUrl != null
        copyDeviceCode.isEnabled = deviceActive && deviceUserCode != null
        copyDeviceUrl.isEnabled = deviceActive && deviceVerificationUrl != null
        logout.isEnabled = canUseAccount
        smokeButton.isEnabled = canUseAccount
        cancel.isEnabled = busy
    }

    fun setBusy(
        busy: Boolean,
        loggedIn: Boolean,
    ) {
        renderButtons(loggedIn, busy, false)
    }
}
