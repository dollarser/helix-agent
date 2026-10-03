package com.helix.extensions.mobileuse

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import java.util.concurrent.atomic.AtomicInteger

/** Local synthetic surface only; it never opens another installed application. */
class OverlayFixtureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            View(this).apply {
                setBackgroundColor(Color.rgb(240, 220, 180))
                setOnClickListener { clicks.incrementAndGet() }
            },
        )
    }

    companion object {
        val clicks = AtomicInteger()
    }
}
