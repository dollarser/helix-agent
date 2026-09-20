package com.helix.runtime.proot.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import com.helix.runtime.proot.ipc.PtySessionKey
import com.helix.runtime.proot.ipc.PtySessionProtocol
import com.helix.runtime.proot.ipc.PtySessionReply
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Explicit user-operation connection. No automatic reconnect, command retry or replay. */
class PtySessionClient(
    context: Context,
) : AutoCloseable {
    private val context = context.applicationContext
    private val ready = CountDownLatch(1)

    @Volatile private var endpoint: IBinder? = null
    private var bound = false
    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(
                name: ComponentName,
                service: IBinder,
            ) {
                endpoint = service
                ready.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                endpoint = null
                ready.countDown()
            }

            override fun onBindingDied(name: ComponentName) {
                endpoint = null
                ready.countDown()
            }

            override fun onNullBinding(name: ComponentName) {
                ready.countDown()
            }
        }

    fun connect() {
        worker()
        check(!bound) { "Manual terminal client already bound" }
        bound =
            context.bindService(
                Intent().setComponent(ComponentName(context.packageName, PtySessionProtocol.SERVICE)),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        check(bound && ready.await(20, TimeUnit.SECONDS) && endpoint != null) { "Manual terminal unavailable" }
    }

    /** Only the application's manual facade calls this transport; it is not registered as an Agent tool. */
    fun request(
        key: PtySessionKey,
        code: Int,
        write: (Parcel) -> Unit = {},
    ): PtySessionReply {
        worker()
        require(code in PtySessionProtocol.START..PtySessionProtocol.ACK)
        val binder = checkNotNull(endpoint) { "Manual terminal disconnected; query original session" }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(PtySessionProtocol.DESCRIPTOR)
            PtySessionProtocol.writeKey(data, key)
            write(data)
            require(data.dataSize() <= PtySessionProtocol.MAX_PARCEL_BYTES)
            check(binder.transact(code, data, reply, 0))
            return PtySessionProtocol.readReply(reply).also { response ->
                require(response.record?.let(key::matches) != false)
            }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun close() {
        endpoint = null
        if (bound) {
            context.unbindService(connection)
            bound = false
        }
    }

    private fun worker() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Manual terminal IPC requires a worker" }
    }
}
