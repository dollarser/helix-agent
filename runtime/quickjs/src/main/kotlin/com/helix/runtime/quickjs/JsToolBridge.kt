@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER") // Same pinned channel as JsNativeHost.

package com.helix.runtime.quickjs

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import app.cash.zipline.QuickJs
import app.cash.zipline.internal.bridge.CallChannel
import com.helix.tools.framework.HostToolBridge

/** Per-execution callback, never a permanent native bridge or a source of task authority. */
internal object JsToolBridge {
    private const val DESCRIPTOR = "com.helix.quickjs.tasktools"
    private const val CALL = IBinder.FIRST_CALL_TRANSACTION
    private const val MAX_BYTES = 256 * 1024
    private const val PREFIX = "helix-tool:"

    fun hostBinder(host: HostToolBridge): IBinder =
        object : Binder() {
            override fun onTransact(
                code: Int,
                data: Parcel,
                reply: Parcel?,
                flags: Int,
            ): Boolean {
                if (code != CALL || reply == null) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(DESCRIPTOR)
                check(host.acceptsPeer(getCallingPid(), getCallingUid())) { "BRIDGE_PEER_REFUSED" }
                require(data.dataAvail() <= MAX_BYTES * 2 + 16) { "BRIDGE_REQUEST_TOO_LARGE" }
                val body = requireNotNull(data.readString())
                require(body.toByteArray(Charsets.UTF_8).size <= MAX_BYTES && data.dataAvail() == 0)
                val result = host.request(body)
                reply.writeNoException()
                reply.writeString(result)
                return true
            }
        }

    fun isRequest(text: String): Boolean = text.startsWith(PREFIX)

    fun call(
        peer: IBinder,
        text: String,
    ): String {
        require(isRequest(text))
        val body = text.removePrefix(PREFIX)
        require(body.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(body)
            check(peer.transact(CALL, data, reply, 0)) { "BRIDGE_DISCONNECTED_RESULT_UNKNOWN" }
            reply.readException()
            return requireNotNull(reply.readString())
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun install(
        js: QuickJs,
        peer: IBinder,
        ownsChannel: Boolean,
    ) {
        if (ownsChannel) {
            js.initOutboundChannel(
                object : CallChannel {
                    override fun call(callJson: String): String = call(peer, callJson)

                    override fun disconnect(instanceName: String): Boolean = false
                },
            )
        }
        js.evaluate(BOOTSTRAP, "helix-task-tools.js")
    }

    private val BOOTSTRAP =
        """
        globalThis.helix = (() => {
          let sequence = 0;
          const bridge = app_cash_zipline_outboundChannel;
          return Object.freeze({call(tool,args={}) {
            const id = 'js_' + (++sequence);
            return JSON.parse(bridge.call('helix-tool:' + JSON.stringify({id,tool,args})));
          }});
        })();
        """.trimIndent()
}
