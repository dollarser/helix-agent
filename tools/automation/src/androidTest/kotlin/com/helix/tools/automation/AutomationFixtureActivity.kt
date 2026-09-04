package com.helix.tools.automation

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** HXA-090's self-built target app; HXA-091/092 will extend it with snapshot/action fixtures. */
class AutomationFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply { text = "Helix M9 automation fixture" })
                addView(Button(context).apply { text = "Fixture button" })
            },
        )
    }
}
