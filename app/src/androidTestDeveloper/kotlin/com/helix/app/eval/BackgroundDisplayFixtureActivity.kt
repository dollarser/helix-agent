package com.helix.app.eval

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Synthetic launcher in the test APK only. No accounts, network, or user files. */
class BackgroundDisplayFixtureActivity : Activity() {
    private var count = 0
    private var visualCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val facts = getSharedPreferences("background-fixture", 0)
        facts
            .edit()
            .clear()
            .putInt("display", display?.displayId ?: -1)
            .commit()
        val result = TextView(this).apply { text = "BACKGROUND_COUNT:0" }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 80, 20, 20)
                addView(TextView(context).apply { text = "BACKGROUND_DISPLAY:${display?.displayId}" })
                addView(result)
                val visual = TextView(context).apply { text = "VISUAL_COUNT:0" }
                addView(visual)
                addView(
                    BackgroundVisualFixtureView(context) {
                        visual.text = "VISUAL_COUNT:${++visualCount}"
                        facts.edit().putInt("visual", visualCount).commit()
                    },
                    LinearLayout.LayoutParams(-1, 330),
                )
                addView(
                    EditText(context).apply {
                        id = android.R.id.edit
                        hint = "Background text"
                    },
                )
                addView(
                    Button(context).apply {
                        id = android.R.id.button1
                        text = "Increment"
                        isAllCaps = false
                        setOnClickListener {
                            result.text = "BACKGROUND_COUNT:${++count}"
                            facts.edit().putInt("clicks", count).commit()
                        }
                    },
                )
            },
        )
    }
}
