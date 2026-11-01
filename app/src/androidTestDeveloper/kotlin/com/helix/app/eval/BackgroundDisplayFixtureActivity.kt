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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val result = TextView(this).apply { text = "BACKGROUND_COUNT:0" }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(20, 80, 20, 20)
                addView(TextView(context).apply { text = "BACKGROUND_DISPLAY:${display?.displayId}" })
                addView(result)
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
                        setOnClickListener { result.text = "BACKGROUND_COUNT:${++count}" }
                    },
                )
            },
        )
    }
}
