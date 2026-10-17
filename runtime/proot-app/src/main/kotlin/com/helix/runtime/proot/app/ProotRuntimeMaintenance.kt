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
        if (sessions.records().any { it.stopProof == null }) throw ProotEnvironmentInUse()
        val runner = ProotJobRunner.get(context)
        val ticket = "maintenance-${UUID.randomUUID()}"
        if (!runner.reserveMaintenance(ticket)) throw ProotEnvironmentInUse()
        return try {
            action()
        } finally {
            runner.releaseMaintenance(ticket)
        }
    }
}

internal class ProotEnvironmentInUse : IllegalStateException()
