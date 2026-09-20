package com.helix.app.terminal

import android.content.Context
import android.os.Binder
import com.helix.runtime.proot.client.PtySessionClient
import com.helix.runtime.proot.ipc.PtySessionKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.helix.runtime.proot.ipc.PtySessionProtocol as Wire

/** One view's connection, never an Activity reference. Detach closes transport even if its reply is lost. */
internal class ManualTerminalConnection(
    context: Context,
    private val key: PtySessionKey,
    private val mayWrite: () -> Boolean,
) : ManualTerminal.Connection {
    private val client = PtySessionClient(context)
    private val death = Binder()
    private val mutex = Mutex()
    private var token: String? = null
    private var closed = false
    private var _isWriter: Boolean = true

    override val isWriter: Boolean
        get() = _isWriter

    // Always close a partially bound transport, then propagate the original failure.
    @Suppress("TooGenericExceptionCaught")
    fun connect(): ManualTerminalConnection {
        try {
            client.connect()
            val reply = client.request(key, Wire.ATTACH) { it.writeStrongBinder(death) }
            token = reply.connection
            _isWriter = (reply.outcome == "OK" && token != null)
            return this
        } catch (failure: Exception) {
            client.close()
            throw failure
        }
    }

    override suspend fun read(cursor: String?): ManualTerminal.Output =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(!closed)
                val page = checkNotNull(client.request(key, Wire.READ) { it.writeString(cursor) }.page)
                ManualTerminal.Output(page.cursor, page.bytes, page.gapBefore, page.eof)
            }
        }

    override suspend fun write(bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                check(!closed && isWriter && mayWrite()) { "Write not permitted in observer mode" }
                client
                    .request(key, Wire.WRITE) { data ->
                        data.writeString(token)
                        Wire.writeBytes(data, bytes)
                    }.outcome
            }
        }

    override suspend fun resize(
        rows: Int,
        columns: Int,
    ) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(!closed)
            if (!isWriter) return@withContext
            val reply =
                client.request(key, Wire.RESIZE) {
                    it.writeInt(rows)
                    it.writeInt(columns)
                }
            check(reply.outcome == "OK")
        }
    }

    override suspend fun detach() =
        withContext(NonCancellable + Dispatchers.IO) {
            mutex.withLock {
                if (!closed) {
                    closed = true
                    try {
                        if (token != null) {
                            client.request(key, Wire.DETACH) { it.writeString(token) }
                        }
                    } finally {
                        token = null
                        client.close()
                    }
                }
            }
        }
}
