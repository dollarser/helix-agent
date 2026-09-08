package com.helix.feature.browser.webview

import android.app.AlertDialog
import android.content.Context
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.widget.EditText

/** One window-bound result; navigation, cancellation and teardown settle it exactly once. */
internal class BrowserJsDialogs(
    private val canShow: () -> Boolean,
) {
    private var cancelPending: (() -> Unit)? = null

    fun cancel() {
        cancelPending?.invoke()
    }

    fun show(
        context: Context,
        message: String,
        result: JsResult,
        promptDefault: String? = null,
        confirm: Boolean = false,
    ): Boolean {
        cancel()
        if (!canShow()) {
            result.cancel()
            return true
        }
        val input = if (result is JsPromptResult) EditText(context).apply { setText(promptDefault.orEmpty()) } else null
        var settled = false
        var dialog: AlertDialog? = null

        fun finish(accepted: Boolean) {
            if (settled) return
            settled = true
            cancelPending = null
            if (!accepted) {
                result.cancel()
            } else if (result is JsPromptResult) {
                result.confirm(input?.text.toString())
            } else {
                result.confirm()
            }
            dialog?.dismiss()
        }
        val builder =
            AlertDialog
                .Builder(context)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> finish(true) }
                .setOnCancelListener { finish(false) }
        if (confirm || input != null) builder.setNegativeButton(android.R.string.cancel) { _, _ -> finish(false) }
        if (input != null) builder.setView(input)
        dialog = builder.create()
        dialog.setOnDismissListener { finish(false) }
        cancelPending = { finish(false) }
        dialog.show()
        return true
    }
}
