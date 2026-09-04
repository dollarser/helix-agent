package com.helix.runtime.proot.core

/**
 * A runtime-lock / runtime-manifest document violated the HXA-080 schema (structure, type,
 * format, cross-field or baseline rule).
 *
 * Fail-closed semantics: any violation — an unknown key, a missing required field, a
 * non-canonical SHA-256, a non-HTTPS URL, a path-traversing reference, a tampered embedded
 * lock — is thrown, never sanitized, defaulted or quarantined silently. Callers (build-time
 * asset gate, on-device installer, handshake) treat the exception as "this document does not
 * constitute version truth" and stop.
 *
 * Extends [IllegalArgumentException] so a corrupt document can never be mistaken for a
 * runtime/IO condition and silently retried as data. [cause] (when present) is the
 * underlying parser failure that made the document undecodable.
 */
class RuntimeLockSchemaException(
    message: String,
) : IllegalArgumentException(message) {
    constructor(message: String, cause: Throwable) : this(message) {
        initCause(cause)
    }
}
