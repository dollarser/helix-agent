package com.helix.tools.framework

/** Declarative retrieval hints. They never grant authority or change execution semantics. */
data class ToolDiscoveryHints(
    val category: String? = null,
    val keywords: Set<String> = emptySet(),
) {
    init {
        require(category == null || Regex("[a-z][a-z0-9_-]{0,63}").matches(category)) {
            "discovery category must be a lowercase identifier"
        }
        require(keywords.size <= 32 && keywords.all { it.isNotBlank() && it.length <= 128 }) {
            "discovery keywords must be bounded, nonblank phrases"
        }
    }
}
