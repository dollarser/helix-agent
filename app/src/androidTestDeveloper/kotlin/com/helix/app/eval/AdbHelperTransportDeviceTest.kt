package com.helix.app.eval

import android.os.Binder
import android.os.Parcel
import com.helix.extensions.mobileuse.automation.AutomationActionStatus
import com.helix.extensions.mobileuse.automation.AutomationDeviceOperation
import com.helix.extensions.mobileuse.automation.AutomationDeviceRequest
import com.helix.extensions.mobileuse.automation.backend.AdbAutomationBackend
import org.junit.Assert.assertEquals
import org.junit.Test

/** Android Binder failure projection, separate from the actual external-process/device journey. */
class AdbHelperTransportDeviceTest {
    @Test fun failureAfterDispatchStaysUnknownAndDisconnectedCallsAreNeverReplayed() {
        var dispatches = 0
        var connected = true
        val remote =
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    dispatches++
                    error("Synthetic lost reply after dispatch")
                }
            }
        val backend = AdbAutomationBackend(remote) { connected }
        val call = AutomationDeviceRequest(AutomationDeviceOperation.GESTURE)
        assertEquals(AutomationActionStatus.ACTION_OUTCOME_UNKNOWN, backend.device(call) { true }.action?.status)
        assertEquals(1, dispatches)
        connected = false
        assertEquals(AutomationActionStatus.ACTION_NOT_DISPATCHED, backend.device(call) { true }.action?.status)
        assertEquals(1, dispatches)
    }
}
