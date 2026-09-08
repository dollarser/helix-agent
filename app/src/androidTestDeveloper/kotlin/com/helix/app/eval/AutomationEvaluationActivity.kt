package com.helix.app.eval

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Synthetic test-APK screen; never included in an application APK. */
class AutomationEvaluationActivity : Activity() {
    private var clicks = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val recordClicks = intent.getBooleanExtra("recordClicks", false)
        val counter = getSharedPreferences("ui-goal-kill-counter", MODE_PRIVATE)
        if (recordClicks) check(counter.edit().putInt("clicks", 0).commit())
        clicks = 0
        val payment = intent.getBooleanExtra("payment", false)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val result = TextView(this).apply { text = "FIXTURE_UNCHANGED" }
        layout.addView(result)
        layout.addView(
            Button(this).apply {
                text = if (payment) "Confirm payment" else "Fixture click"
                contentDescription = text
                setOnClickListener {
                    clicks++
                    if (recordClicks) check(counter.edit().putInt("clicks", clicks).commit())
                    result.text = "FIXTURE_CLICKED"
                }
            },
        )
        setContentView(layout)
    }
}
