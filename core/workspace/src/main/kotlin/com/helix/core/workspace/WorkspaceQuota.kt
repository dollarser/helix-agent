package com.helix.core.workspace

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * Workspace quota (HXA-041: 配额). Enforces a total-size cap on the files under a scope root and
 * answers the headroom needed before a write is admitted.
 *
 * The quota is advisory-by-design and fail-closed: it bounds total on-disk usage, it does not
 * reserve bytes. Two concurrent writers can each pass [hasRoom] and still overshoot together —
 * callers that need a hard guarantee must serialize their writes. After every mutation the
 * caller re-checks usage with [usageBytes].
 */
object WorkspaceQuota {
    /** A write was refused because it would exceed the quota (doc 13: bounded output). */
    class QuotaExceeded(
        val requestedBytes: Long,
        val currentUsageBytes: Long,
        val maxBytes: Long,
    ) : RuntimeException(
            "quota exceeded (requested $requestedBytes, usage $currentUsageBytes, max $maxBytes)",
        ),
        WorkspaceFileError {
        override fun toString(): String =
            "quota exceeded (requested $requestedBytes, usage $currentUsageBytes, max $maxBytes)"
    }

    /**
     * Total size of the regular files under [root], counted recursively. A missing root uses 0.
     *
     * Symlinks are never followed: a symlink entry reports as a link, not the target's size, so
     * an escaping symlink cannot inflate or deflate the figure. The scan is best-effort and
     * cheap, safe to run before and after every mutation.
     * @throws IOException when a directory entry cannot be read (fail closed — a partial usage
     *   figure must not silently under-report usage).
     */
    fun usageBytes(root: Path): Long {
        if (!Files.exists(root)) return 0L
        require(Files.isDirectory(root)) { "root must be a directory" }
        return walk(root)
    }

    /**
     * True when a [bytes]-sized addition fits under [maxBytes] given current usage of [root].
     * The check is a fast pre-admission; it does not reserve capacity.
     */
    fun hasRoom(
        root: Path,
        bytes: Long,
        maxBytes: Long,
    ): Boolean {
        require(bytes >= 0) { "bytes must be >= 0" }
        require(maxBytes >= 0) { "maxBytes must be >= 0" }
        return usageBytes(root) + bytes <= maxBytes
    }

    /**
     * Admits [bytes] against the quota, throwing [QuotaExceeded] when it would overflow.
     */
    fun ensureRoom(
        root: Path,
        bytes: Long,
        maxBytes: Long,
    ) {
        if (!hasRoom(root, bytes, maxBytes)) {
            throw QuotaExceeded(bytes, usageBytes(root), maxBytes)
        }
    }

    private fun walk(root: Path): Long =
        Files.newDirectoryStream(root).use { stream ->
            stream.asSequence().sumOf { entrySize(it) }
        }

    /**
     * Recurses into directories; counts only regular files; never follows symlinks (a symlink
     * reports 0, not the target's size, so an escaping link can't distort the figure).
     */
    private fun entrySize(entry: Path): Long =
        when {
            Files.isSymbolicLink(entry) -> 0L
            Files.isDirectory(entry) -> walk(entry)
            else -> regularFileSize(entry)
        }

    private fun regularFileSize(entry: Path): Long {
        val attrs = Files.readAttributes(entry, BasicFileAttributes::class.java)
        return if (attrs.isRegularFile) attrs.size() else 0L
    }
}

/**
 * A per-workspace quota bound. The default bound mirrors a modest phone-local workspace; a
 * platform adapter may override it per workspace.
 */
data class WorkspaceQuotaPolicy(
    val maxWorkspaceBytes: Long,
) {
    companion object {
        /** Default total workspace cap: 1 GiB. */
        const val DEFAULT_MAX_WORKSPACE_BYTES: Long = 1L shl 30

        val default: WorkspaceQuotaPolicy = WorkspaceQuotaPolicy(DEFAULT_MAX_WORKSPACE_BYTES)
    }
}
