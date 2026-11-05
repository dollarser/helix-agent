package com.helix.extensions.mobileuse.automation.backend

import android.os.IBinder
import android.os.Parcel

/** Read-only access to an already owned display. Never binds, creates, or selects a display. */
internal object BackgroundPreviewTransport {
    const val MAX_BYTES = 256 * 1024

    @Suppress("ReturnCount") // Refuse unsupported IPC and oversized payloads before allocating pixels.
    fun read(
        service: IBinder,
        key: String,
        owner: IBinder,
        width: Int,
        wholeDisplay: Boolean,
    ): ByteArray? {
        require(width in BACKGROUND_PREVIEW_WIDTHS)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ShizukuUiProtocol.DESCRIPTOR)
            data.writeString(key)
            data.writeStrongBinder(owner)
            data.writeInt(width)
            data.writeInt(if (wholeDisplay) 1 else 0)
            if (!service.transact(ShizukuUiProtocol.PREVIEW, data, reply, 0)) return null
            reply.readException()
            val length = reply.readInt()
            if (length !in 1..MAX_BYTES) return null
            reply.setDataPosition(reply.dataPosition() - Int.SIZE_BYTES)
            reply.createByteArray()?.takeIf { reply.dataAvail() == 0 }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
