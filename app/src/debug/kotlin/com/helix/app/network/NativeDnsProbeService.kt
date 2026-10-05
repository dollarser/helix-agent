package com.helix.app.network

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import com.helix.core.policy.network.NativeNetwork

/** Debug-only, non-exported observation of the real subscriptions process. Never performs network I/O. */
class NativeDnsProbeService : Service() {
    private val messenger =
        Messenger(
            Handler(Looper.getMainLooper()) { request ->
                val addresses = NativeNetwork.settings?.lookup("helix-dns-fixture.invalid").orEmpty()
                request.replyTo.send(
                    Message.obtain().apply {
                        data =
                            Bundle().apply {
                                putStringArrayList("addresses", ArrayList(addresses.map { it.hostAddress!! }))
                                putInt("pid", Process.myPid())
                            }
                    },
                )
                true
            },
        )

    override fun onBind(intent: Intent) = messenger.binder
}
