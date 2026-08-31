package com.helix.core.model

/**
 * Stable tool name exposed to the model.
 *
 * Built-in tools use short names (`read`, `write`, `edit`, `bash`) or dotted names
 * (`files.list`, `browser.click`, `code.javascript.run`, `time.now`); MCP tools are forced to
 * `mcp.<server>.<tool>`. Names are fixed by the Tool Registry and can never be registered from
 * model output.
 *
 * Shape: 1..8 dot-separated segments, each 1..64 characters of `[A-Za-z0-9_-]` starting with a
 * letter or digit; total length at most 128 characters.
 */
@JvmInline
value class ToolName(
    val value: String,
) {
    init {
        require(value.length in 1..MAX_TOTAL_LENGTH) { "tool name must be 1..$MAX_TOTAL_LENGTH characters" }
        val segments = value.split(SEPARATOR)
        require(segments.size in 1..MAX_SEGMENTS) { "tool name must have 1..$MAX_SEGMENTS segments" }
        segments.forEach { segment ->
            require(segment.length in 1..MAX_SEGMENT_LENGTH) {
                "tool name segment must be 1..$MAX_SEGMENT_LENGTH characters"
            }
            require(segment.first().isLetterOrDigit()) { "tool name segment must start with a letter or digit" }
            segment.forEach { c ->
                require(c.isLetterOrDigit() || c == '_' || c == '-') {
                    "tool name segment contains invalid character"
                }
            }
        }
    }

    override fun toString(): String = value

    private companion object {
        const val SEPARATOR = "."
        const val MAX_TOTAL_LENGTH = 128
        const val MAX_SEGMENTS = 8
        const val MAX_SEGMENT_LENGTH = 64
    }
}

/**
 * Monotonic, non-negative tool API version. Changing the version invalidates cached approvals
 * (the approval hash includes `toolVersion`).
 */
@JvmInline
value class ToolVersion(
    val value: Int,
) {
    init {
        require(value >= 0) { "tool version must be >= 0" }
    }

    override fun toString(): String = value.toString()
}
