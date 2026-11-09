package com.helix.core.model

/** Preflight for recursive JSON parsers. It does not validate grammar or rewrite wire tokens. */
object JsonInputLimits {
    const val MAX_DEPTH: Int = 64
    const val MAX_NODES: Int = 100000

    /** Scan without recursion or allocations; brackets inside escaped strings are ordinary text. */
    fun requireSafeNesting(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (character in text) {
            if (quoted) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> quoted = false
                }
            } else {
                when (character) {
                    '"' -> {
                        quoted = true
                    }

                    '{', '[' -> {
                        require(depth <= MAX_DEPTH) { "JSON exceeds nesting depth $MAX_DEPTH" }
                        depth++
                    }

                    '}', ']' -> {
                        depth--
                    }
                }
            }
        }
    }
}
