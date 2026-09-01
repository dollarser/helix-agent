package com.helix.core.workspace

import java.nio.file.Path

/**
 * A path inside an arbitrary Helix file scope — a Workspace, a SAF document tree, a user-chosen
 * all-files root, or a browser/automation session directory (platform capabilities doc section 2;
 * architecture doc section 10).
 *
 * [scopeId] is the opaque scope identifier the model sees (doc 10: 模型只看到 scopeId);
 * [relativePath] is the canonical relative path inside that scope, validated exactly like a
 * [WorkspacePath] ([PathSyntax]). The pair is the only form in which a tool argument or model
 * context may reference a file: no adapter, log or error may hand a raw real path to the model.
 */
class FileScopePath(
    scopeIdRaw: String,
    relativePathRaw: String,
) {
    val scopeId: String
    val relativePath: String

    init {
        scopeId = requireValidScopeId(scopeIdRaw)
        val normalized = PathSyntax.normalizeRelative(relativePathRaw)
        // Enforce the model-reference bound here (not in toModelReference) so every instance's
        // reference is bounded and toString/toModelReference can never throw (doc 13: bounded
        // outputs). The reference is "scope:" + scopeId + ":" + relative, so 7 chars of fixed
        // prefix/delimiters are reserved on top of the scope id and the relative path.
        require(normalized.length <= MAX_MODEL_REFERENCE_LENGTH - scopeId.length - 7) {
            "model reference would exceed $MAX_MODEL_REFERENCE_LENGTH characters after normalization"
        }
        relativePath = normalized
    }

    /** True when the path names the scope root itself. */
    val isRoot: Boolean get() = relativePath.isEmpty()

    /** Canonical scope root containing this path. */
    val parent: FileScopePath
        get() =
            if (relativePath.isEmpty()) {
                this
            } else if (relativePath.contains('/')) {
                FileScopePath(scopeId, relativePath.substringBeforeLast('/'))
            } else {
                FileScopePath(scopeId, "")
            }

    /** File name of the last segment (or "." for the root). */
    val name: String
        get() = if (relativePath.isEmpty()) "." else relativePath.substringAfterLast('/')

    /**
     * The stable, bounded, model-safe string form: `scope:<scopeId>:<relativePath>`. This — and
     * only this — is what adapters render into model context or tool arguments (doc 10: 不同
     * scope adapter 不泄漏真实路径给模型).
     *
     * Total on every constructed instance: [init] guarantees the reference length bound, so
     * rendering (including [toString]) can never throw.
     */
    fun toModelReference(): String = "scope:$scopeId:${relativePath.ifEmpty { "." }}"

    override fun toString(): String = toModelReference()

    override fun equals(other: Any?): Boolean =
        other is FileScopePath && other.scopeId == scopeId && other.relativePath == relativePath

    override fun hashCode(): Int = 31 * scopeId.hashCode() + relativePath.hashCode()

    companion object {
        const val MAX_SCOPE_ID_LENGTH = 64
        const val MAX_MODEL_REFERENCE_LENGTH = 512

        /**
         * Parses a model reference of the form produced by [toModelReference] back into a
         * [FileScopePath]. Fails closed on any deviation; the relative part is re-normalized, so
         * a tampered reference cannot smuggle an absolute or escaping path.
         */
        fun fromModelReference(reference: String): FileScopePath {
            require(reference.startsWith("scope:")) { "model reference must start with 'scope:'" }
            val rest = reference.removePrefix("scope:")
            val separator = rest.indexOf(':')
            require(separator > 0) { "model reference must contain a scope id" }
            val scopeId = rest.substring(0, separator)
            val relative = rest.substring(separator + 1)
            // The constructor re-validates scopeId and re-normalizes the relative part, so a
            // tampered reference cannot smuggle an absolute or escaping path. A "." root
            // round-trips to the empty canonical relative path.
            return FileScopePath(scopeId, if (relative == ".") "" else relative)
        }
    }
}

/**
 * Resolves the real root of a scope. Production implementations live in the platform adapters
 * (app/feature modules): they look the scope up in user data and return the directory the scope
 * covers. The resolver never returns a value to the model — only to the containment check in
 * [resolveFileScopePath].
 */
fun interface ScopeRootResolver {
    /**
     * @return the absolute real root of [scopeId].
     * @throws ScopeNotAvailable when the scope is unknown, revoked or unusable.
     */
    fun resolveRoot(scopeId: String): Path
}

private fun requireValidScopeId(scopeId: String): String {
    require(scopeId.length in 1..FileScopePath.MAX_SCOPE_ID_LENGTH) {
        "scopeId must be 1..${FileScopePath.MAX_SCOPE_ID_LENGTH} characters (got ${scopeId.length})"
    }
    require(scopeId.none { it == '/' || it == '\\' || it == ':' }) {
        "scopeId must not contain a separator or ':'"
    }
    require(scopeId.none { isControlChar(it) }) { "scopeId contains a control character" }
    return scopeId
}

/** NUL, C0, DEL, or C1 — never legal in a scope id or path segment. */
private fun isControlChar(c: Char): Boolean {
    val code = c.code
    return code == 0x00 || code in 0x01..0x1F || code == 0x7F || code in 0x80..0x9F
}

/** Scope unknown, revoked, or not usable for this operation (fail closed). */
class ScopeNotAvailable(
    message: String,
) : RuntimeException(message)

/**
 * Resolves a [FileScopePath] to its real absolute path, enforcing the scope boundary:
 * the root comes from [rootResolver] and the path is proven to stay inside it via
 * [PathResolution.resolveWithinRoot]. The returned [Path] is internal to the execution layer;
 * it must not be copied into model context, tool results, logs shown to the user, or audit rows
 * (those carry [FileScopePath.toModelReference] instead).
 *
 * @throws ScopeNotAvailable if the root resolver cannot provide the scope.
 * @throws PathResolutionError if the path is a symlink the policy forbids or escapes the root.
 * @throws IllegalArgumentException on structurally invalid input.
 */
fun resolveFileScopePath(
    path: FileScopePath,
    rootResolver: ScopeRootResolver,
    policy: LinkPolicy = LinkPolicy.REJECT_SYMLINKS,
): Path {
    val root = rootResolver.resolveRoot(path.scopeId)
    val candidate = PathResolution.join(root, path.relativePath)
    return PathResolution.resolveWithinRoot(root, candidate, policy)
}
