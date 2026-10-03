package com.helix.runtime.cli.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Metadata fixture for the integrated-service contract; binding callbacks are injected by the test. */
class CliRuntimeService : Service() {
    override fun onBind(intent: Intent): IBinder? = null
}
