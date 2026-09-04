package com.helix.runtime.cli.app

import android.app.Service
import android.content.Intent
import android.os.IBinder

class CliRuntimeService : Service() {
    override fun onBind(intent: Intent): IBinder =
        CliRuntimeServiceBinder(
            statusProvider = { CliEmbeddedBaseline.status(this) },
            callerVerifier = { uid -> CliCallerVerifier.verify(this, uid) },
        )

    override fun onUnbind(intent: Intent): Boolean = false
}
