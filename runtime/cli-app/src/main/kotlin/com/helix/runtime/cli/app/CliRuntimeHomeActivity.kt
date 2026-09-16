package com.helix.runtime.cli.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** One launcher entry. Opening it does not start OAuth, a companion job or a network probe. */
class CliRuntimeHomeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SubscriptionRuntimeEnvironment.initialize(this)
        title = getString(R.string.subscription_app_name)
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
        column.addView(TextView(this).apply { setText(R.string.runtime_home_help) })
        listOf(
            "Codex" to CodexLoginActivity::class.java,
            "GitHub Copilot" to CopilotLoginActivity::class.java,
            "Claude" to ClaudeLoginActivity::class.java,
            "Grok" to GrokLoginActivity::class.java,
        ).forEach { (label, activity) ->
            column.addView(
                Button(this).apply {
                    text = label
                    setOnClickListener { startActivity(Intent(this@CliRuntimeHomeActivity, activity)) }
                },
            )
        }
        column.addView(
            Button(this).apply {
                setText(R.string.subscription_dns_title)
                setOnClickListener {
                    startActivity(Intent(this@CliRuntimeHomeActivity, SubscriptionNetworkSettingsActivity::class.java))
                }
            },
        )
        SubscriptionScreen.show(this, column, home = true)
    }
}
