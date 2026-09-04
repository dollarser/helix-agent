package com.helix.runtime.proot.ipc

import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import java.nio.charset.StandardCharsets

/**
 * Client side of the hand-rolled protocol: one [IBinder.transact] round trip that
 * yields either the peer's [RuntimeTargetDescriptor] or a stable
 * [UnavailableCause]. This function NEVER throws — every transport and parse
 * failure maps to a closed cause (ADR-0007: stable "Runtime 不可用" states, no
 * fallback executor, no unbounded retry).
 */
object ProotHandshakeClient {
    /** Terminal outcome of one handshake round trip. */
    sealed interface Outcome {
        data class Descriptor(
            val descriptor: RuntimeTargetDescriptor,
        ) : Outcome

        data class Failed(
            val cause: UnavailableCause,
        ) : Outcome
    }

    /**
     * One handshake transact. The try is the function's single expression: every
     * transport failure (binder death, refused transact, RemoteException) maps to
     * one stable typed [Outcome.Failed] — no Throwable ever crosses the APK
     * boundary.
     */
    @Suppress("SwallowedException")
    fun handshake(binder: IBinder): Outcome {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(ProotRuntimeProtocol.INTERFACE_DESCRIPTOR)
            data.writeInt(ProotRuntimeProtocol.PROTOCOL_VERSION)
            val handled = binder.transact(ProotRuntimeProtocol.TX_HANDSHAKE, data, reply, 0)
            if (!handled) return Outcome.Failed(UnavailableCause.BIND_REFUSED)
            // Consume the status word. This protocol carries NO cross-APK exceptions
            // (the server answers with a status byte in every path), so the old API's
            // returned exception is always null here and the new API (34+, void
            // readException that throws) has nothing to throw.
            reply.readException()
            when (reply.readByte()) {
                ProotRuntimeProtocol.REPLY_OK -> {
                    parseDescriptor(reply)
                }

                ProotRuntimeProtocol.REPLY_UNSUPPORTED_PROTOCOL -> {
                    Outcome.Failed(UnavailableCause.PROTOCOL_MISMATCH)
                }

                ProotRuntimeProtocol.REPLY_MANIFEST_FAILED -> {
                    Outcome.Failed(UnavailableCause.HANDSHAKE_FAILED)
                }

                ProotRuntimeProtocol.REPLY_CALLER_MISMATCH -> {
                    Outcome.Failed(UnavailableCause.SIGNATURE_MISMATCH)
                }

                else -> {
                    Outcome.Failed(UnavailableCause.UNKNOWN)
                }
            }
        } catch (e: DeadObjectException) {
            Outcome.Failed(UnavailableCause.DEAD_OBJECT)
        } catch (e: RemoteException) {
            Outcome.Failed(UnavailableCause.UNKNOWN)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * REPLY_OK path: read the parceled PFD read end and decode the manifest it
     * carries; any decode failure is a HANDSHAKE_FAILED (not a transport error).
     * @Suppress("SwallowedException") — see [handshake]: typed outcome, no
     * throwables cross the boundary.
     */
    @Suppress("SwallowedException")
    private fun parseDescriptor(reply: Parcel): Outcome {
        val readEnd =
            reply.readParcelable(ParcelFileDescriptor::class.java.classLoader) as ParcelFileDescriptor?
        if (readEnd == null) return Outcome.Failed(UnavailableCause.HANDSHAKE_FAILED)
        return try {
            val bytes = PfdManifestChannel.readFromStart(readEnd)
            Outcome.Descriptor(RuntimeTargetDescriptorCodec.parse(String(bytes, StandardCharsets.UTF_8)))
        } catch (e: ProotIpcException) {
            Outcome.Failed(UnavailableCause.HANDSHAKE_FAILED)
        }
    }

    /**
     * Validates a freshly received descriptor against the client's expectations.
     * [expectedLockSha256] is the persisted verified anchor's canonical lock
     * fingerprint, or null on the FIRST verification (no anchor yet: structural
     * checks only — the descriptor is then persisted to become the anchor).
     * Returns the failing cause, or null when the descriptor is acceptable.
     */
    fun check(
        descriptor: RuntimeTargetDescriptor,
        expectedProtocolVersion: Int,
        expectedAbi: String,
        expectedLockSha256: String?,
    ): UnavailableCause? =
        when {
            descriptor.protocolVersion != expectedProtocolVersion -> {
                UnavailableCause.PROTOCOL_MISMATCH
            }

            descriptor.abi != expectedAbi -> {
                UnavailableCause.ABI_MISMATCH
            }

            expectedLockSha256 != null && descriptor.lockSha256 != expectedLockSha256 -> {
                UnavailableCause.LOCK_MISMATCH
            }

            else -> {
                null
            }
        }
}
