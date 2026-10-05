package com.helix.extensions.mobileuse.automation.backend

import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.helix.tools.root.HelixRootService

/** Same non-daemon libsu connection; adds a closed UI protocol without adding root.exec. */
class RootAutomationService : HelixRootService() {
    private var ui: ShizukuUiUserService? = null

    override fun onDestroy() {
        try {
            ui?.close()
        } finally {
            super.onDestroy()
        }
    }

    override fun onBind(intent: Intent): IBinder {
        val reads = super.onBind(intent)
        val clicks = ui ?: ShizukuUiUserService(this).also { ui = it }
        val ownerUid = applicationInfo.uid
        return object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                require(getCallingUid() == ownerUid) { "UNTRUSTED_CALLER" }
                return if (code in
                    setOf(ShizukuUiProtocol.GET_UID, ShizukuUiProtocol.CLICK_MATCH, ShizukuUiProtocol.DEVICE)
                ) {
                    clicks.transact(code, data, reply, flags)
                } else {
                    reads.transact(code, data, reply, flags)
                }
            }
        }
    }
}
