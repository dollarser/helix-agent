package com.helix.runtime.proot.core

/**
 * Zip Slip protection for RootFS archive extraction (HXA-082; architecture doc
 * local-code-execution section 6.4 step 6: 拒绝绝对路径、`..`、设备文件和越界
 * symlink).
 *
 * Purely lexical: symlink targets are NEVER resolved against the host filesystem.
 * An absolute symlink target (e.g. `/bin/busybox`) is interpreted relative to the
 * extracted tree root — the PRoot chroot semantics that the target will have at
 * runtime — and is therefore legal as long as it stays inside the tree. This is the
 * normal form of a Linux rootfs (Alpine ships hundreds of absolute symlinks).
 */
object TarPathSafety {
    /**
     * Normalizes a raw tar member name into a safe path relative to the extraction
     * root. Rejects empty names, NUL/newline, backslashes, `..` segments and empty
     * segments (double slashes). A leading `/` is stripped (tar convention); `.`
     * segments are dropped. The result never escapes [destRoot] when joined.
     */
    fun normalizeMemberPath(raw: String): String {
        checkNoControl(raw, "member path")
        checkNoBackslash(raw, "member path")
        // tar writes directory names with a trailing "/" — strip the directory
        // marker (a single one); internal/trailing double slashes still fail below.
        var path = raw.removePrefix("/")
        if (path.endsWith("/")) path = path.dropLast(1)
        val out = StringBuilder()
        for (seg in path.split('/')) {
            when (seg) {
                "" -> {
                    throw TarStreamException("empty path segment in member: $raw")
                }

                "." -> {
                    Unit
                }

                ".." -> {
                    throw TarStreamException("member path escapes the tree: $raw")
                }

                else -> {
                    if (out.isNotEmpty()) out.append('/')
                    out.append(seg)
                }
            }
        }
        // "" is the archive root itself (members named "/", "./", "/./" — the
        // normal first entry of a rootfs tar). Callers must treat it as destRoot.
        return out.toString()
    }

    /**
     * Validates the target of a symlink member at [memberPath]. A relative target is
     * resolved lexically against the member's directory; an absolute target is
     * resolved against the tree root (PRoot chroot semantics, see class KDoc). The
     * target may be dangling — runtime paths such as `/proc` are bound by PRoot and
     * are not shipped in the archive. Returns the target unchanged (as stored).
     */
    fun validateSymlinkTarget(
        target: String,
        memberPath: String,
    ): String {
        checkNoControl(target, "symlink target")
        checkNoBackslash(target, "symlink target")
        var depth = if (target.startsWith("/")) 0 else parentDepth(memberPath)
        // A single leading "/" marks an absolute (tree-root-relative) target; any
        // further empty segment (double slashes, trailing slash) is still rejected.
        // An escape (`..` at depth 0) is recorded, not thrown mid-walk, so the
        // function keeps a bounded throw count.
        var escaped = false
        for (seg in target.removePrefix("/").split('/')) {
            when (seg) {
                "" -> throw TarStreamException("empty path segment in symlink target: $target")
                "." -> Unit
                ".." -> if (depth == 0) escaped = true else depth--
                else -> depth++
            }
        }
        val problem =
            when {
                target.isEmpty() -> "empty symlink target for $memberPath"
                escaped -> "symlink target escapes the tree: $target"
                else -> null
            }
        if (problem != null) throw TarStreamException(problem)
        return target
    }

    private fun parentDepth(memberPath: String): Int = memberPath.count { it == '/' }

    private fun checkNoControl(
        value: String,
        what: String,
    ) {
        if (value.any { it == '\u0000' || it == '\n' || it == '\r' }) {
            throw TarStreamException("control character in $what")
        }
    }

    private fun checkNoBackslash(
        value: String,
        what: String,
    ) {
        if (value.contains('\\')) throw TarStreamException("backslash in $what")
    }
}
