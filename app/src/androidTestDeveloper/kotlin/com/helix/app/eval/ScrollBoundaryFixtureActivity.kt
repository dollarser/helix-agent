package com.helix.app.eval

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Native scroll bounds and a deliberately negative acknowledgement after a real viewport change. */
class ScrollBoundaryFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val receipt = TextView(this).apply { contentDescription = "Scroll receipt" }
        val scroll =
            object : ScrollView(this) {
                var calls = 0

                fun report() {
                    receipt.text = "y=$scrollY;calls=$calls"
                }

                override fun onScrollChanged(
                    x: Int,
                    y: Int,
                    oldX: Int,
                    oldY: Int,
                ) {
                    super.onScrollChanged(x, y, oldX, oldY)
                    report()
                }

                override fun performAccessibilityAction(
                    action: Int,
                    arguments: Bundle?,
                ): Boolean {
                    if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                        action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    ) {
                        calls++
                        report()
                        if (intent.getBooleanExtra("negativeAck", false)) {
                            scrollTo(0, 0)
                            return false
                        }
                    }
                    return super.performAccessibilityAction(action, arguments)
                }
            }.apply {
                contentDescription = "Boundary scroll target"
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        repeat(50) { index ->
                            val row = TextView(context).apply { text = "Boundary row $index" }
                            addView(row, LinearLayout.LayoutParams(-1, 80))
                        }
                    },
                )
            }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(receipt, LinearLayout.LayoutParams(-1, 100))
                if (intent.getBooleanExtra("densePreview", false)) addDensePreview(this)
                addView(scroll, LinearLayout.LayoutParams(-1, 500))
            },
        )
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun addDensePreview(parent: LinearLayout) {
        // This Activity runs in the standalone test package without the target APK's Kotlin runtime.
        val grid = android.widget.GridLayout(this)
        grid.columnCount = 5
        var index = 0
        while (index < 40) {
            val label = TextView(this)
            label.text = "Preview $index"
            label.contentDescription = "Preview label $index"
            label.textSize = 10f
            if (intent.getBooleanExtra("waitScopeTargets", false) && index < 2) {
                label.text = "Wait duplicate target"
                label.setOnClickListener { label.text = "Unexpected wait click" }
            }
            val params = android.widget.GridLayout.LayoutParams()
            params.width = 0
            params.height = 60
            params.columnSpec = android.widget.GridLayout.spec(android.widget.GridLayout.UNDEFINED, 1f)
            grid.addView(label, params)
            index++
        }
        parent.addView(grid, LinearLayout.LayoutParams(-1, -2))
    }
}
