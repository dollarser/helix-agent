package com.helix.core.storage.content

import com.helix.core.storage.internal.asLong
import com.helix.core.storage.internal.asString
import com.helix.core.storage.internal.parseStrictObject

/**
 * Reference to large content stored in files instead of Room (architecture doc 9.2: Room stores
 * references, hashes and metadata only). The reference is content-addressed: the path is
 * derived from the SHA-256 of the content, so integrity is checkable on read.
 */
data class ContentRef(
    val relativePath: String,
    val size: Long,
    val sha256: String,
) {
    init {
        require(size >= 0) { "size must be >= 0, was $size" }
        require(sha256.length == 64 && sha256.all { it in HEX_CHARS }) { "sha256 must be 64 lowercase hex chars" }
        require(relativePath == expectedPath(sha256)) {
            "relativePath must be the content-addressed layout for sha256, was $relativePath"
        }
    }

    /** Canonical storage form: `{"path":"...","size":N,"sha256":"..."}` (ADR-0001 style). */
    fun toStorageString(): String = "{\"path\":\"${escape(relativePath)}\",\"size\":$size,\"sha256\":\"$sha256\"}"

    companion object {
        private val HEX_CHARS: Set<Char> = "0123456789abcdef".toSet()

        fun parse(text: String): ContentRef {
            val fields = parseStrictObject(text)
            require(fields.keys.toList() == listOf("path", "size", "sha256")) {
                "ContentRef storage form requires path, size, sha256 in that order"
            }
            val path = fields.getValue("path").asString("path")
            val size = fields.getValue("size").asLong("size")
            val sha256 = fields.getValue("sha256").asString("sha256")
            return ContentRef(path, size, sha256)
        }

        fun expectedPath(sha256: String): String = "content/${sha256.substring(0, 2)}/$sha256"

        internal fun escape(value: String): String =
            value
                .map { char ->
                    when (char) {
                        '"' -> {
                            "\\\""
                        }

                        '\\' -> {
                            "\\\\"
                        }

                        '\n' -> {
                            "\\n"
                        }

                        '\r' -> {
                            "\\r"
                        }

                        '\t' -> {
                            "\\t"
                        }

                        '\b' -> {
                            "\\b"
                        }

                        '\u000C' -> {
                            "\\f"
                        }

                        else -> {
                            if (char < ' ') {
                                "\\u${char.code.toString(16).padStart(4, '0')}"
                            } else {
                                char.toString()
                            }
                        }
                    }
                }.joinToString("")
    }
}
