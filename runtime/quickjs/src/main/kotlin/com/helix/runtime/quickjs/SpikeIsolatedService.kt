package com.helix.runtime.quickjs

import android.app.Application
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process

/**
 * HXA-050 spike service (doc 03 section 2.2). Proves on-device that a `non-exported`
 * `isolatedProcess` service can be started per instance through
 * `ContextCompat.bindIsolatedService`, reports its own PID/UID/process name, and is
 * recycled by the system after `unbindService`.
 *
 * Deliberately minimal: no AIDL, no execution protocol, no instance-name validation.
 * HXA-051 replaces this with the production `JsExecutionService` contract.
 */
class SpikeIsolatedService : Service() {
    override fun onBind(intent: Intent): IBinder = InfoBinder()

    /**
     * Single-transaction binder. The caller issues `transact([InfoBinder.CODE_INFO], ...)`
     * and receives a [Bundle] with this process' own `pid`, `uid` and process name —
     * the exact values the spike must observe from OUTSIDE the isolated process.
     */
    class InfoBinder : Binder() {
        override fun onTransact(
            code: Int,
            data: Parcel,
            reply: Parcel?,
            flags: Int,
        ): Boolean {
            if (code == CODE_INFO) {
                info().writeToParcel(requireNotNull(reply) { "reply parcel is null" }, 0)
                return true
            }
            return super.onTransact(code, data, reply, flags)
        }

        private fun info(): Bundle =
            Bundle().apply {
                putInt(InfoBinder.KEY_PID, Process.myPid())
                putInt(InfoBinder.KEY_UID, Process.myUid())
                // `Application.getProcessName()` (static, API 28+) instead of
                // `Process.myProcessName()`: the static Process variant does not exist on
                // API 29 (probe-observed NoSuchMethodError on the API 29 emulator).
                putString(InfoBinder.KEY_PROCESS, Application.getProcessName())
            }

        companion object {
            const val CODE_INFO: Int = FIRST_CALL_TRANSACTION
            const val KEY_PID = "pid"
            const val KEY_UID = "uid"
            const val KEY_PROCESS = "process"
        }
    }
}
