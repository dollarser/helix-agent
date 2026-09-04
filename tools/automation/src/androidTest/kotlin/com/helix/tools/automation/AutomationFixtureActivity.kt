package com.helix.tools.automation

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Self-built target surfaces; no external app or another worktree is touched by device tests. */
class AutomationFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        render(intent)
    }

    private fun render(intent: Intent) {
        when (intent.getStringExtra(EXTRA_MODE) ?: MODE_NORMAL) {
            MODE_SENSITIVE -> renderSensitive()
            MODE_SECURE_CUSTOM -> renderSecureCustom()
            else -> renderNormal()
        }
    }

    private fun renderNormal() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val result = TextView(this).apply { text = "idle" }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = "Helix M9 automation fixture" })
                addView(
                    Button(context).apply {
                        text = "Tap target"
                        contentDescription = "Fixture action"
                        setOnClickListener { result.text = "clicked" }
                        setOnLongClickListener {
                            result.text = "long_clicked"
                            true
                        }
                    },
                )
                addView(
                    EditText(context).apply {
                        hint = "Editable fixture"
                        contentDescription = "Text target"
                    },
                )
                addView(result)
                addView(
                    ScrollView(context).apply {
                        contentDescription = "Scroll target"
                        addView(
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                repeat(40) { index ->
                                    addView(TextView(context).apply { text = "scroll row $index" })
                                }
                            },
                        )
                    },
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        500,
                    ),
                )
            },
        )
    }

    private fun renderSensitive() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(
            EditText(this).apply {
                hint = "Fixture password"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            },
        )
    }

    private fun renderSecureCustom() {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(
            View(this).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            },
        )
    }

    companion object {
        const val EXTRA_MODE = "fixture_mode"
        const val MODE_NORMAL = "normal"
        const val MODE_SENSITIVE = "sensitive"
        const val MODE_SECURE_CUSTOM = "secure_custom"
    }
}
