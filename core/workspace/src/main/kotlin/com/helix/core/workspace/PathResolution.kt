package com.helix.core.workspace

import java.nio.file.Files
import java.nio.file.Path

/**
 * Symlink and real-path containment policy for a scope root (security doc: 路径穿越/符号链接
 * 逃逸 — real-path/root check, symlinks never followed by default).
 *
 * The policy is deliberately platform-neutral: it runs on [java.nio.file.Path] so the JVM
 * security tests exercise the same production code the device adapters use.
 */
enum class LinkPolicy {
    /**
     * Default: a symlink anywhere on the candidate chain (ancestor or final segment) is
     * rejected, and a path that resolves outside the real root is rejected.
     */
    REJECT_SYMLINKS,

    /**
     * Only the scope root itself may be a symlink (an adapter may point it at a private
     * directory); symlinked descendants are still required to resolve inside the real root.
     */
    FOLLOW_ROOT,
}

/** Typed rejection produced by the symlink/real-path checks. */
sealed interface PathResolutionError {
    /** [message] is a pre-authored, bounded reason; no filesystem detail is embedded (doc 13). */
    val message: String
}

/** The candidate path resolves outside the real scope root (or the root symlink is not allowed). */
class SymlinkEscapesRoot(
    override val message: String,
) : RuntimeException(message),
    PathResolutionError {
    override fun toString(): String = message
}

/** A segment of the candidate chain is a symlink, which the active [LinkPolicy] forbids. */
class SymlinkInPath(
    override val message: String,
) : RuntimeException(message),
    PathResolutionError {
    override fun toString(): String = message
}

/**
 * Resolves [candidate] (absolute) against [root] and proves the candidate stays inside the real
 * root. The candidate itself may not exist.
 *
 * Steps: (1) a symlinked root is rejected under [LinkPolicy.REJECT_SYMLINKS] (only
 * [LinkPolicy.FOLLOW_ROOT] may follow it); (2) the candidate must start with the root — proven
 * before any segment walk so no walk can climb above the scope root; (3) the root is resolved to
 * its real location and, for a symlinked root, the candidate is re-based onto the real root;
 * (4) under [LinkPolicy.REJECT_SYMLINKS] reject any symlink segment of the candidate chain from
 * the root down to the final segment; (5) require every existing strict ancestor to be a
 * directory (catches `a.txt/b`); (6) require the canonical candidate path, with the final
 * segment not followed, to equal or be contained in the canonical real root. Every violation
 * fails closed with a [PathResolutionError] whose message never embeds raw filesystem details.
 */
object PathResolution {
    /**
     * @return the canonical candidate path (re-based under the real root when the root is a
     *   symlink under [LinkPolicy.FOLLOW_ROOT]).
     * @throws SymlinkInPath when the active policy forbids a symlinked candidate segment.
     * @throws SymlinkEscapesRoot when the root is a symlink the policy forbids, the candidate
     *   is not under the root, or the candidate resolves outside the real root.
     */
    @Suppress("ThrowsCount") // each throw is a distinct fail-closed policy violation, not retryable
    fun resolveWithinRoot(
        root: Path,
        candidate: Path,
        policy: LinkPolicy = LinkPolicy.REJECT_SYMLINKS,
    ): Path {
        require(root.isAbsolute) { "root must be absolute" }
        require(candidate.isAbsolute) { "candidate must be absolute" }

        val rootIsSymlink = Files.isSymbolicLink(root)
        if (rootIsSymlink && policy == LinkPolicy.REJECT_SYMLINKS) {
            throw SymlinkEscapesRoot("scope root must not be a symlink")
        }

        // Containment is proven first and on both policy paths: a candidate that does not
        // start with the root is rejected before any segment walk, so the walk below never
        // climbs above the scope root.
        if (!candidate.startsWith(root)) {
            throw SymlinkEscapesRoot("path escapes the scope root")
        }

        val realRoot: Path
        val target: Path
        if (rootIsSymlink) {
            realRoot = root.toRealPath()
            // Re-base the candidate onto the real root so the final containment check compares
            // like with like (the textual root path is a symlink, not the real location).
            val inside = candidate.subpath(root.nameCount, candidate.nameCount)
            target = realRoot.resolve(inside)
        } else {
            realRoot = root.toRealPath()
            target = candidate
        }

        if (policy == LinkPolicy.REJECT_SYMLINKS) {
            checkNoSymlinks(root, target)
        }

        // Every existing strict ancestor must be a directory; the final segment is exempt
        // (it may be a regular file, a symlink or not exist yet). getParent() returns null at
        // the filesystem root, which ends the walk.
        var ancestor = target.parent
        while (ancestor != null) {
            if (Files.exists(ancestor) && !Files.isDirectory(ancestor)) {
                throw SymlinkEscapesRoot("path ancestor is not a directory")
            }
            ancestor = ancestor.parent
        }

        // Resolve only the deepest existing prefix (its real path resolves ancestor symlinks
        // that may be present under FOLLOW_ROOT), then re-append the still-missing tail
        // textually. A real location outside the real root fails the containment check below.
        var deepest = target
        while (!Files.exists(deepest)) {
            val up = deepest.parent ?: break
            deepest = up
        }
        val resolved = deepest.toRealPath().resolve(deepest.relativize(target))
        if (resolved != realRoot && !resolved.startsWith(realRoot)) {
            throw SymlinkEscapesRoot("path escapes the scope root")
        }
        return resolved
    }

    /** Joins canonical relative segments from [PathSyntax] onto absolute [root] verbatim. */
    fun join(
        root: Path,
        relative: String,
    ): Path {
        if (relative.isEmpty()) return root
        var path = root
        relative.split('/').forEach { path = path.resolve(it) }
        return path
    }

    /**
     * Rejects any symlink segment in [candidate] between [root] (inclusive) and [candidate]
     * (inclusive). The walk stops at [root]: filesystem locations above the scope root (e.g.
     * macOS `/var` → `/private/var`) are outside the scope contract and may legitimately be
     * symlinks.
     */
    private fun checkNoSymlinks(
        root: Path,
        candidate: Path,
    ) {
        var prefix = candidate
        while (true) {
            if (Files.isSymbolicLink(prefix)) {
                throw SymlinkInPath("path must not cross a symlinked segment")
            }
            if (prefix == root) return
            val ancestor = prefix.parent
            if (ancestor == prefix) return
            prefix = ancestor
        }
    }
}
