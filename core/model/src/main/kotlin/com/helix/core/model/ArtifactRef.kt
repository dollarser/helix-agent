package com.helix.core.model

/**
 * Opaque reference to a stored artifact (bounded summary replacement for oversized tool
 * results, plan bodies, execution inputs, ...).
 *
 * The reference deliberately carries no path, no file I/O and no interpretation in this module:
 * it cannot be used to construct a filesystem path (path separators are rejected), and only the
 * storage/workspace layers that registered the artifact know how to resolve it, for example via
 * `read(offset, maxBytes)` chunked access (HXA-041 owns the real storage).
 *
 * Shape: 1..128 characters of `[A-Za-z0-9._:-]`.
 */
@JvmInline
value class ArtifactRef(
    val value: String,
) {
    init {
        require(value.length in 1..MAX_LENGTH) { "artifact ref must be 1..$MAX_LENGTH characters" }
        value.forEach { c ->
            require(
                c in 'a'..'z' ||
                    c in 'A'..'Z' ||
                    c in '0'..'9' ||
                    c == '_' ||
                    c == '-' ||
                    c == '.' ||
                    c == ':',
            ) { "artifact ref contains invalid character" }
        }
    }

    override fun toString(): String = value

    private companion object {
        const val MAX_LENGTH = 128
    }
}
