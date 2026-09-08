package com.helix.runtime.cli.client

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import com.helix.core.model.ModelEvent
import java.io.IOException
import java.security.MessageDigest

internal data class CliModelWireResult(
    val status: Int = CliRuntimeProtocol.REPLY_JOB_INVALID,
    val record: CliModelJobRecord? = null,
    val events: List<ModelEvent>? = null,
    val cause: CliRuntimeVerification.Cause? = null,
)

internal object CliModelJobWire {
    fun transact(
        binder: IBinder,
        code: Int,
        jobId: String,
        requestSha256: String?,
        payload: ByteArray?,
    ): CliModelWireResult {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        var upload: CliRequestPipe? = null
        val result =
            try {
                data.writeInterfaceToken(CliRuntimeProtocol.DESCRIPTOR)
                data.writeString(jobId)
                requestSha256?.let(data::writeString)
                if (payload != null) {
                    upload = CliRequestPipe(payload, jobId)
                    data.writeParcelable(upload.readEnd, 0)
                }
                if (binder.transact(code, data, reply, 0)) {
                    decodeReply(reply)
                } else {
                    CliModelWireResult()
                }
            } catch (_: RemoteException) {
                CliModelWireResult(cause = CliRuntimeVerification.Cause.HANDSHAKE_FAILED)
            } catch (_: IOException) {
                CliModelWireResult(cause = CliRuntimeVerification.Cause.HANDSHAKE_FAILED)
            } catch (_: RuntimeException) {
                CliModelWireResult(cause = CliRuntimeVerification.Cause.HANDSHAKE_FAILED)
            } finally {
                reply.recycle()
                data.recycle()
                upload?.close()
            }
        return if (upload?.hasFailed == true) {
            CliModelWireResult(cause = CliRuntimeVerification.Cause.HANDSHAKE_FAILED)
        } else {
            result
        }
    }

    private fun decodeReply(reply: Parcel): CliModelWireResult {
        val status = reply.readInt()
        val record =
            if (status in Replies.RECORD_REPLIES) {
                reply.readString()?.let { CliModelJobRecordCodec.decode(it) }
            } else {
                null
            }
        val eventPayload =
            if (record != null && reply.readInt() == 1) {
                val output =
                    reply.readParcelable<ParcelFileDescriptor>(ParcelFileDescriptor::class.java.classLoader)
                        ?: return CliModelWireResult()
                CliPfdChannel.read(output, CliModelEventCodec.MAX_BYTES)
            } else {
                null
            }
        val events =
            eventPayload?.let {
                require(record?.outputSha256 == cliPayloadSha256(it))
                CliModelEventCodec.decode(it)
            }
        return if (status in Replies.RECORD_REPLIES &&
            record == null
        ) {
            CliModelWireResult()
        } else {
            CliModelWireResult(status, record, events)
        }
    }

    private object Replies {
        val RECORD_REPLIES =
            setOf(
                CliRuntimeProtocol.REPLY_JOB_ACCEPTED,
                CliRuntimeProtocol.REPLY_JOB_DUPLICATE,
                CliRuntimeProtocol.REPLY_JOB_STATE,
            )
    }
}

internal fun cliPayloadSha256(bytes: ByteArray): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
