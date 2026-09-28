package com.helix.core.workspace.memory

import java.security.MessageDigest
import java.time.Instant

/** A logical identity supplied by an explicit project registry, never inferred from a path. */
data class ProjectMemoryScopeKey(
    val value: String,
) {
    init {
        require(value.matches(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")))
    }
}

sealed interface MemoryScope {
    data object Global : MemoryScope

    data class Project(
        val key: ProjectMemoryScopeKey,
    ) : MemoryScope
}

data class MemoryEntry(
    val path: String,
    val markdown: String,
    val hash: String,
    val updatedAt: Long,
) {
    val type: String get() =
        markdown.lineSequence().firstOrNull { it.startsWith("type: ") }?.removePrefix("type: ")
            ?: "reference"
}

/** Small, deliberately non-executable frontmatter subset; no YAML object construction or includes. */
object MemoryMarkdown {
    const val MAX_BYTES = 32_768
    val types = setOf("user", "feedback", "project", "reference")

    fun encode(
        type: String,
        source: String,
        body: String,
        now: Long,
    ): String {
        require(type in types)
        require(source.isNotBlank() && source.length <= 256 && source.none { it.isISOControl() })
        require(body.isNotBlank())
        val quotedSource = source.replace("\\", "\\\\").replace("\"", "\\\"")
        val text =
            buildString {
                appendLine("---")
                appendLine("type: $type")
                appendLine("source: \"$quotedSource\"")
                appendLine("trust: untrusted")
                appendLine("updated_at: ${Instant.ofEpochMilli(now)}")
                appendLine("---")
                appendLine()
                append(body)
            }
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "MEMORY_TOO_LARGE" }
        return text
    }

    fun hash(text: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
