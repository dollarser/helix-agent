package com.helix.runtime.quickjs

/**
 * HXA-051 execution protocol constants (architecture doc local-code-execution §3/§4).
 *
 * Pure JVM object: the closed sets below (transaction codes, inline threshold, detail cap)
 * are unit-tested without the Android SDK. The service binder and the main-process client
 * both consume these constants, so both sides of the protocol share one source of truth.
 */
object JsProtocol {
    /** Bump when the EXECUTE/INFO wire layout changes; the service rejects other versions. */
    const val PROTOCOL_VERSION: Int = 1

    // Transaction codes. `FIRST_CALL_TRANSACTION` mirrors the literal value of
    // `android.os.Binder.FIRST_CALL_TRANSACTION` (1) and is kept here so the closed code
    // set is testable on the JVM without the android SDK.
    const val FIRST_CALL_TRANSACTION: Int = 1

    /** Service reports its own PID/UID (isolation observation point). */
    const val CODE_INFO: Int = FIRST_CALL_TRANSACTION

    /** Executes one request on this service instance. */
    const val CODE_EXECUTE: Int = FIRST_CALL_TRANSACTION + 1

    /** Sets the instance's interrupt flag (main-process watchdog / cancellation). */
    const val CODE_INTERRUPT: Int = FIRST_CALL_TRANSACTION + 2

    val TRANSACTION_CODES: Set<Int> = setOf(CODE_INFO, CODE_EXECUTE, CODE_INTERRUPT)

    /**
     * Maximum number of source/input/output bytes carried inline in a Parcel. Binder
     * transactions have a bounded buffer; payloads above this travel through a
     * `ParcelFileDescriptor` instead (doc 03 §3.1).
     */
    const val PARCEL_INLINE_MAX_BYTES: Int = 64 * 1024

    /** Bounded detail-message length in results (never more than this is carried back). */
    const val MAX_DETAIL_CHARS: Int = 2048

    /** EXECUTE flag: test-only crash-injection seam (see [JsExecutionService]). */
    const val FLAG_CRASH_INJECTION: Int = 1 shl 0
}
