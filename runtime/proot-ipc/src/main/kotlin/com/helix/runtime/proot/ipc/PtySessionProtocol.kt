package com.helix.runtime.proot.ipc

import android.os.IBinder
import android.os.Parcel
import com.helix.runtime.proot.core.PtyOutputPage
import com.helix.runtime.proot.core.PtySessionRecord
import com.helix.runtime.proot.core.PtySessionRecordCodec

/** Manual USER identity, never a ToolCall or an authorization token. */
data class PtySessionKey(
    val sessionId: String,
    val generation: String,
    val executionId: String,
) {
    init {
        listOf(sessionId, generation, executionId).forEach { require(it.matches(Regex("[A-Za-z0-9_-]{1,64}"))) }
    }

    fun matches(record: PtySessionRecord): Boolean =
        with(record.origin) {
            sessionId == this@PtySessionKey.sessionId && generation == this@PtySessionKey.generation &&
                executionId == this@PtySessionKey.executionId
        }
}

data class PtySessionReply(
    val record: PtySessionRecord?,
    val connection: String? = null,
    val page: PtyOutputPage? = null,
    val outcome: String = "OK",
)

/** Bounded, private same-APK IPC. Queries never replay a submission or acquire a foreground owner. */
object PtySessionProtocol {
    const val DESCRIPTOR = "com.helix.runtime.proot.ManualPty.v1"
    const val SERVICE = "com.helix.runtime.proot.app.ProotTerminalService"
    const val START = IBinder.FIRST_CALL_TRANSACTION
    const val QUERY = START + 1
    const val ATTACH = START + 2
    const val DETACH = START + 3
    const val WRITE = START + 4
    const val RESIZE = START + 5
    const val READ = START + 6
    const val STOP = START + 7
    const val ACK = START + 8
    const val MAX_PARCEL_BYTES = 32 * 1024
    const val DEFAULT_LEASE_MS = 2 * 60 * 60 * 1000L
    const val MAX_LEASE_MS = 8 * 60 * 60 * 1000L
    const val IDLE_MS = 30 * 60 * 1000L

    fun writeKey(
        parcel: Parcel,
        key: PtySessionKey,
    ) {
        listOf(key.sessionId, key.generation, key.executionId).forEach(parcel::writeString)
    }

    fun readKey(parcel: Parcel): PtySessionKey =
        PtySessionKey(
            requireNotNull(parcel.readString()),
            requireNotNull(parcel.readString()),
            requireNotNull(parcel.readString()),
        )

    fun writeBytes(
        parcel: Parcel,
        bytes: ByteArray?,
    ) {
        parcel.writeInt(bytes?.size ?: -1)
        if (bytes != null) parcel.writeByteArray(bytes)
    }

    fun readBytes(
        parcel: Parcel,
        limit: Int,
    ): ByteArray? {
        val count = parcel.readInt()
        require(count in -1..limit)
        return if (count < 0) null else ByteArray(count).also(parcel::readByteArray)
    }

    fun writeReply(
        parcel: Parcel,
        reply: PtySessionReply,
    ) {
        parcel.writeNoException()
        parcel.writeString(reply.outcome)
        writeBytes(parcel, reply.record?.let(PtySessionRecordCodec::encode))
        parcel.writeString(reply.connection)
        parcel.writeString(reply.page?.cursor)
        writeBytes(parcel, reply.page?.bytes)
        parcel.writeInt(if (reply.page?.gapBefore == true) 1 else 0)
        parcel.writeInt(if (reply.page?.eof == true) 1 else 0)
        check(parcel.dataSize() <= MAX_PARCEL_BYTES)
    }

    fun readReply(parcel: Parcel): PtySessionReply {
        require(parcel.dataSize() <= MAX_PARCEL_BYTES)
        parcel.readException()
        val outcome = requireNotNull(parcel.readString()).also { require(it.length <= 64) }
        val record = readBytes(parcel, PtySessionRecordCodec.MAX_BYTES)?.let(PtySessionRecordCodec::decode)
        val connection = parcel.readString()?.also { require(it.length <= 64) }
        val cursor = parcel.readString()?.also { require(it.length <= 160) }
        val bytes = readBytes(parcel, 8192)
        val gap = parcel.readInt().also { require(it in 0..1) } == 1
        val eof = parcel.readInt().also { require(it in 0..1) } == 1
        require(parcel.dataAvail() == 0 && ((cursor == null) == (bytes == null)))
        return PtySessionReply(
            record,
            connection,
            cursor?.let { PtyOutputPage(it, requireNotNull(bytes), gap, eof) },
            outcome,
        )
    }
}
