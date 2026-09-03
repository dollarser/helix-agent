package com.helix.runtime.quickjs

import android.os.Parcel
import android.os.ParcelFileDescriptor

/**
 * HXA-051 hand-rolled Parcel wire layout for the `JsExecutionService` binder
 * (transaction codes in [JsProtocol]).
 *
 * One canonical reader/writer pair shared by the service and the main-process client so
 * both sides of the protocol cannot drift. Deliberately AIDL-free: three small
 * transactions, a pinned protocol version, and no generated stub surface to maintain
 * (project style, same as the HXA-050 spike binder).
 *
 * EXECUTE data layout (write order == read order):
 * 1. `protocolVersion: Int`
 * 2. `request: JsExecutionRequest` (parcelable, doc 03 §3.1 shape + deadline)
 * 3. `sourcePfd: ParcelFileDescriptor?` — read-only, present iff source is above the
 *    inline cap
 * 4. `inputPfd: ParcelFileDescriptor?` — read-only, present iff input is above the inline
 *    cap
 * 5. `outputPfd: ParcelFileDescriptor?` — caller-provided writable file for the result
 * 6. `sourceTotalBytes: Int` — total source length (cross-checked against the inline part)
 * 7. `inputTotalBytes: Long` — total input length, 0 = no input
 * 8. `flags: Int` — bit set from [JsProtocol.FLAG_CRASH_INJECTION]
 * 9. `crashAfterMs: Int` — crash-injection delay (test seam only, see
 *    [JsExecutionService])
 *
 * EXECUTE reply: `protocolVersion: Int`, `result: JsExecutionResult`.
 * INFO reply: a [android.os.Bundle] with the service's own `pid`/`uid`.
 */
internal object JsExecutionWire {
    /** Raised on any malformed/opaque protocol violation; mapped to a stable failure, never success. */
    class ProtocolException(
        message: String,
    ) : Exception(message)

    /** Everything the EXECUTE transaction carries beyond the §3.1 request parcelable. */
    data class ExecuteEnvelope(
        val sourcePfd: ParcelFileDescriptor?,
        val inputPfd: ParcelFileDescriptor?,
        val outputPfd: ParcelFileDescriptor?,
        val sourceTotalBytes: Int,
        val inputTotalBytes: Long,
        val flags: Int,
        val crashAfterMs: Int,
    )

    fun writeExecute(
        data: Parcel,
        request: JsExecutionRequest,
        envelope: ExecuteEnvelope,
    ) {
        data.writeInt(JsProtocol.PROTOCOL_VERSION)
        data.writeParcelable(request, 0)
        writePfd(data, envelope.sourcePfd)
        writePfd(data, envelope.inputPfd)
        writePfd(data, envelope.outputPfd)
        data.writeInt(envelope.sourceTotalBytes)
        data.writeLong(envelope.inputTotalBytes)
        data.writeInt(envelope.flags)
        data.writeInt(envelope.crashAfterMs)
    }

    fun readExecute(data: Parcel): Pair<JsExecutionRequest, ExecuteEnvelope> {
        val version = data.readInt()
        if (version != JsProtocol.PROTOCOL_VERSION) {
            throw ProtocolException("unsupported protocol version $version")
        }
        val request =
            data.readParcelable(JsExecutionRequest::class.java.classLoader) as? JsExecutionRequest
                ?: throw ProtocolException("missing JsExecutionRequest in EXECUTE parcel")
        val sourcePfd = readPfd(data)
        val inputPfd = readPfd(data)
        val outputPfd = readPfd(data)
        val sourceTotalBytes = data.readInt()
        val inputTotalBytes = data.readLong()
        val flags = data.readInt()
        val crashAfterMs = data.readInt()
        return request to
            ExecuteEnvelope(sourcePfd, inputPfd, outputPfd, sourceTotalBytes, inputTotalBytes, flags, crashAfterMs)
    }

    fun writeResult(
        reply: Parcel,
        result: JsExecutionResult,
    ) {
        reply.writeInt(JsProtocol.PROTOCOL_VERSION)
        reply.writeParcelable(result, 0)
    }

    fun readResult(reply: Parcel): JsExecutionResult {
        val version = reply.readInt()
        if (version != JsProtocol.PROTOCOL_VERSION) {
            throw ProtocolException("unsupported protocol version $version")
        }
        val result =
            reply.readParcelable(JsExecutionResult::class.java.classLoader) as? JsExecutionResult
                ?: throw ProtocolException("missing JsExecutionResult in EXECUTE reply")
        return result
    }

    fun writeInfo(
        reply: Parcel,
        pid: Int,
        uid: Int,
    ) {
        val bundle =
            android.os.Bundle().apply {
                putInt(KEY_PID, pid)
                putInt(KEY_UID, uid)
            }
        reply.writeBundle(bundle)
    }

    fun readInfo(reply: Parcel): Pair<Int, Int> {
        val bundle = reply.readBundle(null) ?: throw ProtocolException("missing INFO bundle")
        return bundle.getInt(KEY_PID) to bundle.getInt(KEY_UID)
    }

    private fun writePfd(
        parcel: Parcel,
        pfd: ParcelFileDescriptor?,
    ) {
        if (pfd == null) {
            parcel.writeByte(0)
        } else {
            parcel.writeByte(1)
            parcel.writeFileDescriptor(pfd.fileDescriptor)
        }
    }

    private fun readPfd(parcel: Parcel): ParcelFileDescriptor? =
        if (parcel.readByte() == 1.toByte()) parcel.readFileDescriptor() else null

    const val KEY_PID: String = "pid"

    const val KEY_UID: String = "uid"
}
