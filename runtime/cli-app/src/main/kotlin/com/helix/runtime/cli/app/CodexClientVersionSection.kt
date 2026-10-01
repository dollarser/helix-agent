package com.helix.runtime.cli.app

import android.app.Activity
import android.app.AlertDialog
import android.text.InputFilter
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Explicit local user setting. Saving neither connects an account nor downloads/executes software. */
internal class CodexClientVersionSection(
    private val activity: Activity,
) {
    private val settings = codexClientSettings(activity)
    private val value = TextView(activity)
    private val edit =
        Button(activity).apply {
            id = R.id.codex_client_version_edit
            setText(R.string.codex_client_version_edit)
            setOnClickListener { showEditor() }
        }
    val view =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(value)
            addView(edit)
        }

    init {
        refresh()
    }

    fun setEnabled(enabled: Boolean) {
        edit.isEnabled = enabled
    }

    private fun refresh() {
        value.text = activity.getString(R.string.codex_client_version_current, settings.current())
    }

    private fun showEditor() {
        val input =
            EditText(activity).apply {
                id = R.id.codex_client_version_input
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                filters = arrayOf(InputFilter.LengthFilter(64))
                setText(settings.current())
                setSelectAllOnFocus(true)
            }
        val dialog =
            AlertDialog
                .Builder(activity)
                .setTitle(R.string.codex_client_version_title)
                .setMessage(R.string.codex_client_version_help)
                .setView(input)
                .setPositiveButton(R.string.codex_client_version_save, null)
                .setNeutralButton(R.string.codex_client_version_default, null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                save(input, dialog) { settings.save(input.text.toString()) }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                save(input, dialog) { settings.reset() }
            }
        }
        dialog.show()
    }

    private fun save(
        input: EditText,
        dialog: AlertDialog,
        update: () -> String,
    ) {
        try {
            update()
            refresh()
            dialog.dismiss()
        } catch (_: IllegalArgumentException) {
            input.error = activity.getString(R.string.codex_client_version_invalid)
        } catch (_: IllegalStateException) {
            input.error = activity.getString(R.string.codex_client_version_save_failed)
        }
    }
}
