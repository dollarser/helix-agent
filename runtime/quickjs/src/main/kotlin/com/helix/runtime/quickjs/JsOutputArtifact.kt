package com.helix.runtime.quickjs

/**
 * HXA-054 verified-artifact verification (architecture doc local-code-execution
 * §4.6/§4.8).
 *
 * The client accepts a SUCCESS result only after verifying the materialized output
 * artifact (the inline bytes, or the bytes re-materialized from the caller's output
 * PFD file) against the service's own declaration:
 *
 * - exact size — [bytes.size] must equal [declaredBytes];
 * - exact SHA-256 — the hash is INDEPENDENTLY RECOMPUTED on the host side from the
 *   bytes actually received (the host-side landing point of the verified artifact,
 *   doc 03 §4.8); the service's self-reported hash is never trusted blindly;
 * - the HXA-052 output contract — exactly one JSON document, at most
 *   [maxOutputBytes] UTF-8 bytes.
 *
 * Any violation returns a stable reason the client maps to a stable
 * [JsExecutionStatus.UNKNOWN]: a mismatched artifact is NEVER accepted as SUCCESS
 * and is never silently truncated or re-interpreted.
 *
 * Pure JVM: the mismatch branches (size, hash, contract) are unit-tested directly
 * without Android or a live service — the device chain cannot manufacture a
 * declaration that does not match the bytes the real service wrote, so this pure
 * function is the JVM injection point for those branches (no production debug seam
 * is added for them).
 */
internal object JsOutputArtifact {
    /**
     * Returns null when [bytes] verifies against the declaration, otherwise a stable
     * reason (the caller degrades the SUCCESS to UNKNOWN carrying that reason).
     */
    fun verify(
        bytes: ByteArray,
        declaredBytes: Long,
        declaredSha256Hex: String,
        maxOutputBytes: Int,
    ): String? =
        when {
            bytes.size.toLong() != declaredBytes -> "output size ${bytes.size} != declared $declaredBytes"
            JsHash.sha256Hex(bytes) != declaredSha256Hex -> "output SHA-256 mismatch"
            else -> JsOutputContract.validate(bytes, maxOutputBytes)?.let { "output contract violation: $it" }
        }
}
