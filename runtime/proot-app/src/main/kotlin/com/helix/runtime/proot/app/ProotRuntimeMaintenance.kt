package com.helix.runtime.proot.app

import android.content.Context
import com.helix.runtime.proot.core.PtySessionStore
import java.io.File
import java.util.UUID

/** Repair and removal share execution admission with live jobs and manual sessions in :proot. */
internal object ProotRuntimeMaintenance {
    fun <T> run(
        context: Context,
        action: () -> T,
    ): T {
        val sessions = PtySessionStore(File(context.filesDir, "terminal-sessions"))
        check(sessions.records().none { it.stopProof == null }) { "Stop and reconcile the manual terminal first" }
        val runner = ProotJobRunner.get(context)
        val ticket = "maintenance-${UUID.randomUUID()}"
        check(runner.reserveDetached(ticket)) { "Runtime execution is busy" }
        return try {
            action()
        } finally {
            runner.releaseDetached(ticket)
        }
    }
}
