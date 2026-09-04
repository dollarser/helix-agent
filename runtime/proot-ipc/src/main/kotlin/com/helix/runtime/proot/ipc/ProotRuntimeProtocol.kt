package com.helix.runtime.proot.ipc

import android.os.IBinder

/**
 * Wire constants of the cross-APK PRoot Runtime protocol (HXA-083; architecture doc
 * local-code-execution section 6.6, ADR-0007).
 *
 * Both APKs of the signed set (main app `com.helix.agent[.developer]` and companion
 * `com.helix.runtime.proot`) bundle this module; the hand-rolled [IBinder] protocol
 * below deliberately mirrors the `:runtime:quickjs` onTransact style (no AIDL, no
 * generated stubs): the client writes a strict request parcel, the server validates
 * the interface token and protocol version, and the reply carries a bounded
 * manifest over a pipe [android.os.ParcelFileDescriptor].
 */
object ProotRuntimeProtocol {
    /** Current protocol revision; the client rejects anything it cannot consume. */
    const val PROTOCOL_VERSION = 1

    /** Interface token written by the client and enforced by the server. */
    const val INTERFACE_DESCRIPTOR = "com.helix.runtime.proot.IRuntimeService/1"

    /**
     * Signature-level permission guarding the exported companion service (section
     * 6.6): only an APK signed with the set's key can even attempt a bind.
     */
    const val PERMISSION_BIND = "com.helix.permission.BIND_PROOT_RUNTIME"

    /** Companion package (independent applicationId/UID, section 6.6). */
    const val RUNTIME_PACKAGE = "com.helix.runtime.proot"

    /** Bound-only service; cold-started by an explicit [android.content.ComponentName] bind. */
    const val SERVICE_CLASS = "com.helix.runtime.proot.app.ProotRuntimeService"

    /** Minimal user-gated settings/repair entry (ADR-0007 decision 1). */
    const val REPAIR_ACTIVITY_CLASS = "com.helix.runtime.proot.app.ProotRepairActivity"

    /** Main-app packages of the signed set that may bind (consumer + developer flavors). */
    val MAIN_APP_PACKAGES: Set<String> = setOf("com.helix.agent", "com.helix.agent.developer")

    /**
     * Hard cap for ANY manifest crossing the wire (handshake descriptor today; job
     * input/output manifests from HXA-084). Both ends enforce it; oversized payloads
     * are a protocol failure, never a partial read.
     */
    const val MAX_MANIFEST_BYTES = 64L * 1024L

    /** Cold-start + handshake budget; exceeded -> stable HANDSHAKE_TIMEOUT, no retry storm. */
    const val HANDSHAKE_DEADLINE_MS = 20_000L

    // ------------------------------------------------------------------
    // Transaction codes (hand-rolled; FIRST_CALL_TRANSACTION + stable offsets)
    // ------------------------------------------------------------------

    /** Reply status: manifest follows over a pipe PFD. */
    const val REPLY_OK: Byte = 0

    /** The client offered a protocol version the server cannot serve. */
    const val REPLY_UNSUPPORTED_PROTOCOL: Byte = 1

    /** The server could not build a valid manifest (corrupt embedded lock, etc.). */
    const val REPLY_MANIFEST_FAILED: Byte = 2

    /**
     * The transaction's calling identity failed the server's caller re-verification
     * (section 6.6). The client maps this to SIGNATURE_MISMATCH. Every transaction
     * is verified — this is the ONLY place the live cross-APK caller identity exists
     * (see ProotRuntimeServiceBinder).
     */
    const val REPLY_CALLER_MISMATCH: Byte = 3

    const val TX_HANDSHAKE = IBinder.FIRST_CALL_TRANSACTION

    /**
     * DEBUG-BUILD-ONLY crash-injection seam (same house pattern as
     * `:runtime:quickjs`): the debug companion serves this transaction to kill its
     * own process mid-binding so the device test can prove the binder-death path
     * (DeadObjectException -> DEAD_OBJECT -> cold rebind) on a real device.
     * Release builds never serve it.
     */
    const val TX_DEBUG_SELF_KILL = IBinder.FIRST_CALL_TRANSACTION + 1

    /**
     * DEBUG-BUILD-ONLY intent extra (companion service): when set, the debug
     * companion's onBind returns a NULL binder so the device test can drive the
     * client's onNullBinding path (an immediate BIND_REFUSED, never a timeout).
     * Release builds ignore it.
     */
    const val EXTRA_DEBUG_NULL_BIND = "com.helix.runtime.proot.extra.debug_null_bind"
}
