package com.helix.runtime.proot.app

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process

/** Debug-only fixed native journeys. No arbitrary command, environment or path IPC. */
class PtyNativeProbeService : Service() {
    override fun onBind(intent: Intent): IBinder = endpoint

    private val endpoint =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (code !in 1..7) return super.onTransact(code, data, reply, flags)
                check(getCallingUid() == Process.myUid())
                data.enforceInterface("com.helix.runtime.proot.PtyNativeProbe")
                check(data.dataAvail() == 0)
                val result = runProbe(code)
                requireNotNull(reply).writeNoException()
                reply.writeInt(Process.myPid())
                reply.writeString(result)
                return true
            }
        }

    @Suppress("TooGenericExceptionCaught") // A failed diagnostic returns failure, never a successful execution result.
    private fun runProbe(code: Int): String =
        try {
            val probe = PtyNativeJourney(this)
            when (code) {
                1 -> probe.interactive()
                2 -> probe.failedExec()
                3 -> probe.closeCycles()
                4 -> probe.signalAndLimits()
                5 -> probe.prootClosure(quit = false)
                6 -> probe.prootClosure(quit = true)
                7 -> PtySessionWorkerJourney(this).run(probe::startInteractive)
            }
            "OK"
        } catch (failure: Exception) {
            "FAILED:${failure.javaClass.simpleName}:${failure.message.orEmpty().take(512)}"
        }
}
