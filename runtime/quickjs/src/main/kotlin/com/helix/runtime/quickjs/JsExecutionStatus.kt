package com.helix.runtime.quickjs

/**
 * HXA-051 closed set of execution outcomes (roadmap M5 / doc 03 §4).
 *
 * Production semantics (one result per execution, never retried by the client):
 *
 * - [SUCCESS] — evaluate returned a JSON-encodable value; output bytes + SHA-256 are present.
 * - [TIMEOUT] — the wall-time deadline passed (service-side interrupt fired, or the
 *   main-process watchdog gave up on the Binder interaction after the 1 s grace window).
 * - [INTERRUPTED] — an explicit interrupt (cancellation) arrived before the deadline.
 * - [OOM] — QuickJS out-of-memory; a JS-level error, the service process survives.
 * - [JS_ERROR] — any other JS-level failure (syntax error, thrown error, stack overflow,
 *   blocked dynamic compilation).
 * - [OUTPUT_LIMIT] — the result exceeds `maxOutputBytes`, exceeds the inline parcel cap
 *   without an output PFD, or is not JSON-encodable (circular/too-deep result).
 * - [CRASHED] — the isolated service process died (Binder death); the outcome is unknown.
 *   The same execution is never replayed; a later execution uses a fresh instance.
 * - [CANCELLED] — cancelled before the execution started (no service instance bound).
 * - [REQUEST_REJECTED] — the request violated a limit/protocol rule before execution.
 * - [BIND_FAILED] — binding the isolated instance failed or did not connect in time.
 * - [UNKNOWN] — anything unclassifiable. A catch-all NEVER produces [SUCCESS].
 *
 * The enum order and names are a frozen wire contract: results carry [name] over the
 * parcel and [fromWire] maps unknown values to [UNKNOWN] instead of failing.
 */
enum class JsExecutionStatus {
    SUCCESS,
    TIMEOUT,
    INTERRUPTED,
    OOM,
    JS_ERROR,
    OUTPUT_LIMIT,
    CRASHED,
    CANCELLED,
    REQUEST_REJECTED,
    BIND_FAILED,
    UNKNOWN,
    ;

    val isSuccess: Boolean
        get() = this == SUCCESS

    companion object {
        /** The frozen closed set, in wire order. */
        val CLOSED_SET: List<JsExecutionStatus> = entries.toList()

        /** Maps a wire name to a status; unknown/null values degrade to [UNKNOWN]. */
        fun fromWire(name: String?): JsExecutionStatus = entries.firstOrNull { it.name == name } ?: UNKNOWN
    }
}
