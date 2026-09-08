package com.helix.app.diagnostics

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView

/** Debug-only shell fixture: exercises the ordinary app process without an instrumentation handler. */
class DiagnosticFailureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = getString(com.helix.app.R.string.app_name) })
        when (intent.getStringExtra("phase")) {
            "crash" -> {
                Handler(Looper.getMainLooper()).postDelayed({
                    throw IllegalStateException("hxa104-private-marker-never-in-preview")
                }, 3_000L)
            }

            "anr" -> {
                Handler(Looper.getMainLooper()).postDelayed({ Thread.sleep(120_000L) }, 3_000L)
            }

            else -> {
                finish()
            }
        }
    }
}
