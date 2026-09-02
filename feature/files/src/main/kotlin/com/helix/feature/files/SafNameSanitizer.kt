package com.helix.feature.files

/**
 * Sanitizes an UNTRUSTED provider-supplied display name (doc 07: a SAF provider may lie about
 * the display name) into a legal workspace file-name segment.
 *
 * Path separators are replaced (not dropped) so distinct names stay distinct and readable;
 * control characters (NUL, C0, DEL, C1) are dropped; the result is trimmed, capped at
 * [MAX_LENGTH] and re-trimmed so it can never be blank or exceed [com.helix.core.workspace.PathSyntax]
 * segment rules. A name that survives as nothing usable — or as a dot segment — falls back to
 * [fallback]. The result is deterministic in the input: no timestamps, no randomness.
 */
object SafNameSanitizer {
    /** Well under PathSyntax.MAX_SEGMENT_LENGTH (255) to leave room for extension/encoding. */
    const val MAX_LENGTH = 200

    /** Name used when the provider reports none or the name sanitizes to nothing. */
    const val FALLBACK = "imported-file"

    fun sanitize(
        raw: String?,
        fallback: String = FALLBACK,
    ): String {
        val stripped =
            raw
                .orEmpty()
                .map { if (it == '/' || it == '\\' || it == ':') '_' else it }
                .filterNot { isControl(it) }
                .joinToString("")
        val capped = stripped.trim().take(MAX_LENGTH).trim()
        if (capped.isEmpty() || capped == "." || capped == "..") return fallback
        return capped
    }

    /** NUL, C0, DEL, or C1 — never legal in a file-name segment (same set as FileScopePath). */
    private fun isControl(c: Char): Boolean {
        val code = c.code
        return code == 0x00 || code in 0x01..0x1F || code == 0x7F || code in 0x80..0x9F
    }
}
