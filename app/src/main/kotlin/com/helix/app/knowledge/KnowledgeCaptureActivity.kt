package com.helix.app.knowledge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.helix.app.HelixApplication
import com.helix.app.MainActivity
import kotlinx.coroutines.launch

/** Keep the recipient task alive while temporary grants are materialized by the app service. */
class KnowledgeCaptureActivity : ComponentActivity() {
    class CaptureState : androidx.lifecycle.ViewModel() {
        var collection: kotlinx.coroutines.Job? = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = (application as HelixApplication).knowledge
        val state = androidx.lifecycle.ViewModelProvider(this)[CaptureState::class.java]
        if (state.collection == null && savedInstanceState == null) {
            state.collection =
                service.capture(
                    intent,
                    callingPackage ?: referrer?.host ?: "Android share (source not supplied)",
                )
        }
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        // Do not finish here: the Activity owns the temporary incoming content grants.
        lifecycleScope.launch {
            state.collection?.join()
            finish()
        }
    }
}
