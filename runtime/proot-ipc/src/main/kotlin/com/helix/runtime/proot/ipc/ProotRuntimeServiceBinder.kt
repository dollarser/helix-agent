package com.helix.runtime.proot.ipc

import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor

/**
 * Server side of the hand-rolled [Binder] protocol (HXA-083; same house style as
 * `:runtime:quickjs` — no AIDL). The companion service returns one instance from
 * [android.app.Service.onBind] UNCONDITIONALLY; the platform's signature
 * permission guaranteed the same signing set before the bind was delivered.
 *
 * Caller re-verification (section 6.6) happens HERE, not in onBind: `onBind` is a
 * main-thread lifecycle callback, NOT an inbound binder transaction — it carries no
 * caller identity (`Binder.getCallingUid()` there returns the service's OWN uid).
 * [onTransact] is the only place with the live cross-APK caller identity, so EVERY
 * transaction is re-verified against [callerVerifier] (injected by the service as
 * the real `ProotCallerVerifier.verify`); a failure answers
 * [ProotRuntimeProtocol.REPLY_CALLER_MISMATCH] — a stable protocol status byte,
 * never a cross-APK exception.
 *
 * [manifestProvider] and [callerVerifier] must be total and fast: they run on the
 * Binder thread, and any failure degrades to a [ProotRuntimeProtocol.REPLY_*]
 * status byte — the process never crashes the client's handshake into an unknown
 * state.
 */
class ProotRuntimeServiceBinder(
    private val manifestProvider: () -> ByteArray,
    private val callerVerifier: (Int) -> Boolean,
    private val debugSelfKill: Boolean = false,
) : Binder() {
    // @Suppress("ReturnCount") — one return per distinct protocol outcome;
    // @Suppress("SwallowedException") — the reply channel carries status bytes
    // only: a wrong interface token and a failed manifest write both answer
    // REPLY_*_FAILED, and no Throwable ever crosses the APK boundary.
    @Suppress("ReturnCount", "SwallowedException")
    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int,
    ): Boolean {
        if (reply == null) return false
        // The live cross-APK caller identity exists ONLY here (see class KDoc):
        // verify on every transaction, before reading any request data. The
        // handshake is low-frequency, so the PackageManager cost is acceptable;
        // a warm process serving multiple clients re-verifies per transaction.
        if (!callerVerifier(Binder.getCallingUid())) {
            reply.writeNoException()
            reply.writeByte(ProotRuntimeProtocol.REPLY_CALLER_MISMATCH)
            return true
        }
        // Debug-only crash-injection seam (see TX_DEBUG_SELF_KILL): kills this
        // process so the device test can drive the binder-death path. Only a
        // VERIFIED caller may fire it.
        if (debugSelfKill && code == ProotRuntimeProtocol.TX_DEBUG_SELF_KILL) {
            android.os.Process.killProcess(android.os.Process.myPid())
            return true
        }
        if (code != ProotRuntimeProtocol.TX_HANDSHAKE) {
            return super.onTransact(code, data, reply, flags)
        }
        // A wrong interface token is a protocol violation, not a transport error:
        // answer with the unsupported-protocol status byte (no cross-APK exception
        // is ever written — the reply channel is status-byte + manifest only).
        val interfaceOk =
            try {
                data.enforceInterface(ProotRuntimeProtocol.INTERFACE_DESCRIPTOR)
                true
            } catch (e: SecurityException) {
                false
            }
        val clientProtocol = if (interfaceOk) data.readInt() else -1
        if (clientProtocol != ProotRuntimeProtocol.PROTOCOL_VERSION) {
            reply.writeNoException()
            reply.writeByte(ProotRuntimeProtocol.REPLY_UNSUPPORTED_PROTOCOL)
            return true
        }
        val manifest = runCatching { manifestProvider() }.getOrNull()
        if (manifest == null || manifest.size.toLong() > ProotRuntimeProtocol.MAX_MANIFEST_BYTES) {
            reply.writeNoException()
            reply.writeByte(ProotRuntimeProtocol.REPLY_MANIFEST_FAILED)
            return true
        }
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        try {
            PfdManifestChannel.writeToEnd(writeEnd, manifest)
        } catch (e: ProotIpcException) {
            readEnd.close()
            reply.writeNoException()
            reply.writeByte(ProotRuntimeProtocol.REPLY_MANIFEST_FAILED)
            return true
        }
        reply.writeNoException()
        reply.writeByte(ProotRuntimeProtocol.REPLY_OK)
        reply.writeParcelable(readEnd, 0)
        return true
    }
}
