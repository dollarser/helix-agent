package com.helix.runtime.quickjs

/**
 * HXA-052 client-side output contract (architecture doc local-code-execution §4.6).
 *
 * On [JsExecutionStatus.SUCCESS] the service MUST return the wrapper's
 * `JSON.stringify(...)` text: exactly one JSON document, UTF-8 encoded, at most
 * `maxOutputBytes` bytes. The client re-checks BOTH properties on the bytes it
 * actually received (inline or re-materialized from the output PFD) and fails CLOSED
 * to a stable [JsExecutionStatus.UNKNOWN] on any violation — it never silently
 * truncates and never falls back to interpreting the bytes as raw text or base64.
 *
 * Scope note on "double encoding": a quoted JSON string is itself a legal JSON
 * document (it is the wrapper's legitimate output for `return "…"`), so a
 * double-encoded document is structurally indistinguishable from a string result in
 * the ambiguous case. The service encoder is a pinned byte-for-byte pass-through for
 * string results (JsResultJson, unit-tested), so the double-encoding corruption class
 * is prevented at the source; this contract rejects the detectable class — anything
 * that is not a valid JSON document at all (raw text, base64 blobs, bare JS values).
 *
 * Pure JVM: unit-tested without Android.
 */
object JsOutputContract {
    /**
     * Returns null when [outputUtf8] satisfies the output contract for
     * [maxOutputBytes], otherwise a stable rejection reason (the caller maps it to a
     * stable failure — never to success).
     */
    fun validate(
        outputUtf8: ByteArray,
        maxOutputBytes: Int,
    ): String? {
        if (outputUtf8.size.toLong() > maxOutputBytes) {
            return "output ${outputUtf8.size} bytes exceeds maxOutputBytes $maxOutputBytes"
        }
        return if (JsJsonDocument.isValidJson(outputUtf8)) {
            null
        } else {
            "output is not a valid JSON document"
        }
    }
}
