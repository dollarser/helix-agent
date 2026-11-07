package com.helix.core.storage

/** Literal substring search, including Chinese; preserve UTF-16 offsets and ignore-case semantics. */
object SearchText {
    fun fold(text: String): String =
        buildString(text.length) {
            text.forEach { append(it.uppercaseChar().lowercaseChar()) }
        }
}
