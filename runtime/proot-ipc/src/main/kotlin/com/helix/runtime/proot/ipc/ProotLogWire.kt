package com.helix.runtime.proot.ipc

import android.os.Parcel
import com.helix.runtime.proot.core.JobLogPage
import com.helix.runtime.proot.core.JobLogSpool

/** Additive v1 log protocol; old peers reject its new transaction without a submit fallback. */
object ProotLogWire {
    const val VERSION = 1
    const val MAX_REPLY_BYTES = 32 * 1024

    fun write(
        reply: Parcel,
        page: JobLogPage?,
    ) {
        reply.writeNoException()
        reply.writeByte(
            if (page ==
                null
            ) {
                ProotRuntimeProtocol.REPLY_JOB_UNAVAILABLE
            } else {
                ProotRuntimeProtocol.REPLY_JOB_STATE
            },
        )
        if (page != null) {
            reply.writeInt(VERSION)
            reply.writeString(page.cursor)
            reply.writeInt(page.stream)
            reply.writeByteArray(page.bytes)
            reply.writeInt(if (page.eof) 1 else 0)
            reply.writeInt(if (page.truncated) 1 else 0)
        }
    }

    fun read(reply: Parcel): JobLogPage? {
        require(reply.dataSize() <= MAX_REPLY_BYTES)
        reply.readException()
        if (reply.readByte() != ProotRuntimeProtocol.REPLY_JOB_STATE) return null
        require(reply.readInt() == VERSION)
        val cursor = requireNotNull(reply.readString())
        require(cursor.length <= 256)
        val stream = reply.readInt()
        require(stream in 1..2)
        val bytes = requireNotNull(reply.createByteArray())
        require(bytes.size <= JobLogSpool.CHUNK_BYTES)
        val eof = reply.readInt()
        val truncated = reply.readInt()
        require(eof in 0..1 && truncated in 0..1 && reply.dataAvail() == 0)
        return JobLogPage(cursor, stream, bytes, eof == 1, truncated == 1)
    }
}

interface ProotLogHandler {
    fun readLog(
        jobId: String,
        inputManifestSha256: String,
        cursor: String?,
    ): JobLogPage?
}
