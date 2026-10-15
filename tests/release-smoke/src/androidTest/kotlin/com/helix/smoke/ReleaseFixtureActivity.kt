package com.helix.smoke

import android.app.Activity
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicInteger

/** All observable effects belong to this disposable test APK, never a third-party app. */
class ReleaseFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        layout.addView(
            TextView(this).apply {
                text = "R8 fixture header"
                textSize = 24f
                setPadding(20, 40, 20, 40)
            },
        )
        layout.addView(
            Button(this).apply {
                text = "R8 target button"
                setOnClickListener { ReleaseFixtureState.clicks.incrementAndGet() }
            },
        )
        layout.addView(
            View(this).apply {
                contentDescription = "R8 gesture surface"
                setBackgroundColor(0xffbbddee.toInt())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                setOnTouchListener { _, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) ReleaseFixtureState.downs.incrementAndGet()
                    true
                }
                addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    val origin = IntArray(2)
                    getLocationOnScreen(origin)
                    ReleaseFixtureState.x = origin[0] + width / 2
                    ReleaseFixtureState.y = origin[1] + height / 2
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        setContentView(layout)
    }
}

internal object ReleaseFixtureState {
    val clicks = AtomicInteger()
    val downs = AtomicInteger()

    @Volatile var x = 0

    @Volatile var y = 0
}
