package com.helix.runtime.cli.app

import android.app.Activity
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.text.DateFormat
import java.util.Date

/** Manual Runtime-owned configuration; no exported mutation intent or model-facing tool. */
class SubscriptionNetworkSettingsActivity : Activity() {
    private val settings get() = SubscriptionRuntimeEnvironment.initialize(this)
    private lateinit var host: EditText
    private lateinit var addresses: EditText
    private lateinit var duration: Spinner
    private lateinit var feedback: TextView
    private lateinit var rows: LinearLayout
    private val hours = listOf(1, 24, 168)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.subscription_dns_title)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(TextView(this).apply { setText(R.string.subscription_dns_help) })
        column.addView(TextView(this).apply { setText(R.string.subscription_dns_host) })
        host =
            EditText(this).apply {
                id = R.id.subscription_dns_host
                setText(getString(R.string.subscription_dns_default_host))
                setSingleLine(true)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            }
        column.addView(host)
        column.addView(TextView(this).apply { setText(R.string.subscription_dns_addresses) })
        addresses =
            EditText(this).apply {
                id = R.id.subscription_dns_addresses
                // Editable, opt-in preset verified 2026-09-10; never installed on app startup.
                val existing = settings.entries().firstOrNull { it.hostname == "chatgpt.com" }
                setText(existing?.addresses?.joinToString("\n") ?: "104.18.32.47\n172.64.155.209")
                setMinLines(2)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
        column.addView(addresses)
        column.addView(TextView(this).apply { setText(R.string.subscription_dns_duration) })
        duration =
            Spinner(this).apply {
                id = R.id.subscription_dns_duration
                adapter =
                    ArrayAdapter(
                        this@SubscriptionNetworkSettingsActivity,
                        android.R.layout.simple_spinner_dropdown_item,
                        listOf(
                            getString(R.string.subscription_dns_hour),
                            getString(R.string.subscription_dns_day),
                            getString(R.string.subscription_dns_week),
                        ),
                    )
                setSelection(1)
            }
        column.addView(duration)
        column.addView(
            Button(this).apply {
                setText(R.string.subscription_dns_save)
                setOnClickListener { save() }
            },
        )
        feedback = TextView(this)
        column.addView(feedback)
        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(rows)
        column.addView(
            Button(this).apply {
                setText(R.string.subscription_dns_clear)
                setOnClickListener { mutate { settings.clear() } }
            },
        )
        SubscriptionScreen.show(this, column)
    }

    override fun onResume() {
        super.onResume()
        renderRows()
    }

    private fun save() {
        try {
            mutate {
                settings.save(
                    host.text.toString(),
                    addresses.text.toString(),
                    hours[duration.selectedItemPosition],
                )
            }
        } catch (_: IllegalArgumentException) {
            feedback.setText(R.string.subscription_dns_invalid)
        }
    }

    private fun mutate(action: () -> Unit) {
        try {
            action()
            feedback.setText(R.string.subscription_dns_saved)
            renderRows()
        } catch (_: IllegalStateException) {
            feedback.setText(R.string.subscription_dns_save_failed)
        }
    }

    private fun renderRows() {
        rows.removeAllViews()
        val entries = settings.entries()
        if (entries.isEmpty()) rows.addView(TextView(this).apply { setText(R.string.subscription_dns_empty) })
        entries.forEach { entry ->
            rows.addView(
                TextView(this).apply {
                    val status =
                        if (entry.active(System.currentTimeMillis())) {
                            R.string.subscription_dns_active
                        } else {
                            R.string.subscription_dns_expired
                        }
                    text =
                        getString(
                            R.string.subscription_dns_entry,
                            entry.hostname,
                            entry.addresses.joinToString(", "),
                            getString(status),
                            DateFormat
                                .getDateTimeInstance(
                                    DateFormat.SHORT,
                                    DateFormat.SHORT,
                                ).format(Date(entry.expiresAtMillis)),
                        )
                    setTextIsSelectable(true)
                },
            )
            val buttons = LinearLayout(this)
            buttons.addView(
                Button(this).apply {
                    setText(R.string.subscription_dns_edit)
                    setOnClickListener {
                        host.setText(entry.hostname)
                        addresses.setText(entry.addresses.joinToString("\n"))
                        duration.setSelection(1)
                        host.requestFocus()
                    }
                },
            )
            buttons.addView(
                Button(this).apply {
                    setText(R.string.subscription_dns_delete)
                    setOnClickListener { mutate { settings.remove(entry.hostname) } }
                },
            )
            rows.addView(buttons)
        }
    }
}
