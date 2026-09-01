package com.helix.core.workspace

/**
 * A path inside one Helix-managed workspace (architecture doc section 10).
 *
 * A [WorkspacePath] is never a plain string: construction completes every normalization and
 * rejection step (doc 10: NUL 检查、分隔符归一化、绝对路径拒绝、`./..` 解析、根路径确认), so
 * [value] is always canonical — forward-slash separated, dot-free, relative to the workspace
 * root. Tool code can therefore render or log a [WorkspacePath] without ever re-interpreting it,
 * and the tool layer must not concatenate real paths itself (doc 10).
 *
 * The model only ever sees [value], a relative workspace path such as `notes/todo.txt`; the
 * workspace root location on device belongs to the platform adapter and is never exposed here.
 */
class WorkspacePath(
    raw: String,
) {
    val value: String

    init {
        value = PathSyntax.normalizeRelative(raw)
        require(value.length <= MAX_CANONICAL_LENGTH) {
            "workspace path exceeds $MAX_CANONICAL_LENGTH characters after normalization"
        }
    }

    /** True when the path names the workspace root itself (the empty canonical form). */
    val isRoot: Boolean get() = value.isEmpty()

    /** Canonical directory containing this path; the root stays the root. */
    val parent: WorkspacePath
        get() =
            if (value.isEmpty()) {
                this
            } else if (value.contains('/')) {
                WorkspacePath(value.substringBeforeLast('/'))
            } else {
                WorkspacePath("")
            }

    /** File name of the last segment (or "." for the root). */
    val name: String
        get() = if (value.isEmpty()) "." else value.substringAfterLast('/')

    /** Concatenates a relative sub-path, re-normalizing the result. */
    fun resolve(relative: String): WorkspacePath =
        WorkspacePath(listOf(value, relative).filter { it.isNotEmpty() }.joinToString("/"))

    override fun equals(other: Any?): Boolean = other is WorkspacePath && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    private companion object {
        const val MAX_CANONICAL_LENGTH = 17_000
    }
}
