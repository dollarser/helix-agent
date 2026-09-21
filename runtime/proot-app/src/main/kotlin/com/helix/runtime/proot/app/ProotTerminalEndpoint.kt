package com.helix.runtime.proot.app

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.helix.runtime.proot.core.PtyInputConnection
import com.helix.runtime.proot.ipc.PtySessionKey
import com.helix.runtime.proot.ipc.PtySessionReply
import com.helix.runtime.proot.ipc.PtySessionProtocol as Wire

/** Each attached view supplies a death token. A dead view loses input, not the running shell. */
internal class ProotTerminalEndpoint(
    private val service: ProotTerminalService,
    private val host: ProotTerminalHost,
) : Binder() {
    private val connections = ProotJobOwners()
    private val connectionSessions = mutableMapOf<String, String>()

    @Suppress("TooGenericExceptionCaught")
    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (code !in Wire.START..Wire.ACK || reply == null) return super.onTransact(code, data, reply, flags)
        try {
            require(ProotCallerVerifier.verify(service, getCallingUid()))
            require(data.dataSize() <= Wire.MAX_PARCEL_BYTES)
            data.enforceInterface(Wire.DESCRIPTOR)
            val key = Wire.readKey(data)
            val response = synchronized(host) { dispatch(code, key, data) }
            Wire.writeReply(reply, response)
        } catch (failure: Exception) {
            android.util.Log.w("ManualPty", "Manual terminal request failed", failure)
            reply.setDataSize(0)
            Wire.writeReply(reply, PtySessionReply(null, outcome = "REQUEST_FAILED_QUERY_ORIGINAL"))
        }
        return true
    }

    private fun dispatch(
        code: Int,
        key: PtySessionKey,
        data: Parcel,
    ): PtySessionReply =
        when (code) {
            Wire.START -> {
                val workspace = requireNotNull(data.readString()).also { require(it.length <= 4096) }
                val lease = data.readLong()
                empty(data)
                host.start(key, workspace, lease) { service.promote(key) }
            }

            Wire.QUERY, Wire.STOP, Wire.ACK -> {
                control(code, key, data)
            }

            Wire.ATTACH -> {
                attach(key, data)
            }

            Wire.DETACH -> {
                detach(key, data)
            }

            Wire.WRITE -> {
                write(key, data)
            }

            Wire.RESIZE -> {
                resize(key, data)
            }

            Wire.READ -> {
                val cursor = data.readString()?.also { require(it.length <= 160) }
                empty(data)
                val live = host.live(key)
                PtySessionReply(live.record, page = live.output.read(cursor))
            }

            else -> {
                error("Unsupported terminal operation")
            }
        }

    private fun control(
        code: Int,
        key: PtySessionKey,
        data: Parcel,
    ): PtySessionReply {
        empty(data)
        val record =
            when (code) {
                Wire.STOP -> {
                    host.stop(key)
                }

                Wire.ACK -> {
                    val ack = host.acknowledge(key)
                    val tokensForSession = connectionSessions.filterValues { it == key.sessionId }.keys.toList()
                    for (t in tokensForSession) {
                        connections.release(t)
                        connectionSessions.remove(t)
                    }
                    ack
                }

                else -> {
                    host.query(key)
                }
            }
        return PtySessionReply(record, outcome = if (record == null) "NOT_FOUND" else "OK")
    }

    private fun attach(
        key: PtySessionKey,
        data: Parcel,
    ): PtySessionReply {
        val owner = requireNotNull(data.readStrongBinder())
        empty(data)
        val live = host.live(key)
        val token = live.attach()
        if (token != null) {
            connectionSessions[token] = key.sessionId
            host.activity(key.sessionId, attached = true)
            connections.watch(token, owner) {
                synchronized(host) {
                    connectionSessions.remove(token)
                    if (live.detach(token)) host.activity(key.sessionId, attached = false)
                }
            }
        }
        return PtySessionReply(live.record, token, outcome = if (token == null) "WRITER_BUSY" else "OK")
    }

    private fun detach(
        key: PtySessionKey,
        data: Parcel,
    ): PtySessionReply {
        val token = token(data)
        empty(data)
        val live = host.live(key)
        connectionSessions.remove(token)
        val removed = live.detach(token)
        if (removed) host.activity(key.sessionId, attached = false)
        connections.release(token)
        return PtySessionReply(live.record, outcome = if (removed) "OK" else "DETACHED")
    }

    private fun write(
        key: PtySessionKey,
        data: Parcel,
    ): PtySessionReply {
        val token = token(data)
        val bytes = requireNotNull(Wire.readBytes(data, 8192))
        empty(data)
        val live = host.live(key)
        val admission = live.write(token, bytes)
        if (admission == PtyInputConnection.Admission.ACCEPTED) host.activity(key.sessionId)
        return PtySessionReply(live.record, outcome = admission.name)
    }

    private fun resize(
        key: PtySessionKey,
        data: Parcel,
    ): PtySessionReply {
        val rows = data.readInt()
        val columns = data.readInt()
        empty(data)
        val live = host.live(key)
        live.resize(rows, columns)
        return PtySessionReply(live.record)
    }

    private fun token(data: Parcel): String = requireNotNull(data.readString()).also { require(it.length in 1..64) }

    private fun empty(data: Parcel) {
        require(data.dataAvail() == 0)
    }

    fun close() {
        connections.close()
    }
}
