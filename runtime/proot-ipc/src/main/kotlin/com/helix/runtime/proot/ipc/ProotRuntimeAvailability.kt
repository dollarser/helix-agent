package com.helix.runtime.proot.ipc

/**
 * Stable availability of the PRoot companion (ADR-0007 decisions 3/6, section 6.7).
 *
 * Every failure path funnels into [Unavailable] with a closed [UnavailableCause] —
 * there is NO fallback to the main-app shell, QuickJS or any other executor, and no
 * reason string ever carries a path, UID or raw peer data. [Verified] carries the
 * persisted execution-target anchor; whether the process happens to be alive is
 * deliberately NOT a field: the next use re-binds and re-handshakes.
 */
sealed interface ProotRuntimeAvailability {
    /** Short, stable, user-presentable label (no diagnostics). */
    val label: String

    data class Verified(
        val descriptor: RuntimeTargetDescriptor,
        val verifiedAtEpochMs: Long,
    ) : ProotRuntimeAvailability {
        override val label: String = "verified"
    }

    data class Unavailable(
        val cause: UnavailableCause,
    ) : ProotRuntimeAvailability {
        override val label: String = cause.label
    }
}

/** Closed cause set; the UI (HXA-085) maps each to exactly one user-visible state. */
enum class UnavailableCause(
    val label: String,
) {
    /** Companion package not present on the device. */
    NOT_INSTALLED("runtime_not_installed"),

    /** Package installed but disabled by the user/developer options. */
    PACKAGE_DISABLED("runtime_disabled"),

    /** Package in the force-stopped state; only the user-gated repair entry recovers it. */
    PACKAGE_FORCED_STOPPED("runtime_forced_stopped"),

    /** Companion is signed with a different key than the main app's signed set. */
    SIGNATURE_MISMATCH("runtime_signature_mismatch"),

    /** The service refused the bind (caller check failed) or the bind never completed. */
    BIND_REFUSED("runtime_bind_refused"),

    /** Cold-start + handshake exceeded the deadline; no automatic retry. */
    HANDSHAKE_TIMEOUT("runtime_handshake_timeout"),

    /** The peer reported a protocol revision this client cannot consume. */
    PROTOCOL_MISMATCH("runtime_protocol_mismatch"),

    /** The reported runtime ABI is not the baseline ABI of this device set. */
    ABI_MISMATCH("runtime_abi_mismatch"),

    /** The reported lock fingerprint differs from the persisted verified anchor. */
    LOCK_MISMATCH("runtime_lock_mismatch"),

    /** The handshake manifest was malformed or exceeded the size cap. */
    HANDSHAKE_FAILED("runtime_handshake_failed"),

    /** The peer process died mid-operation; the job must reconcile by id, never replay. */
    DEAD_OBJECT("runtime_dead_object"),

    /** Anything unclassifiable; still fail-closed, never a fallback. */
    UNKNOWN("runtime_unavailable"),
}
