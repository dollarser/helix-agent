package com.helix.app.export

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.UUID

/** Local owned staging only. Never touches a SAF target or follows a symlink. Caller holds export mutex. */
internal object ExportStagingCleanup {
    fun collect(
        root: Path,
        activeOperation: String?,
        now: Long,
    ): Int {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return 0
        require(Files.isDirectory(root, NOFOLLOW_LINKS))
        var removed = 0
        Files.list(root).use { children ->
            children.iterator().asSequence().filter { candidate(it, activeOperation, now) }.take(256).forEach { child ->
                remove(child)
                removed++
            }
        }
        return removed
    }

    private fun candidate(
        child: Path,
        activeOperation: String?,
        now: Long,
    ): Boolean {
        val name = child.fileName.toString()
        val owned = runCatching { UUID.fromString(name).toString() == name }.getOrDefault(false)
        if (!owned || name == activeOperation) return false
        return Files.isDirectory(child, NOFOLLOW_LINKS) &&
            now - Files.getLastModifiedTime(child, NOFOLLOW_LINKS).toMillis() >= DAY
    }

    private fun remove(child: Path) =
        Files.walk(child).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
        }

    private const val DAY = 86_400_000L
}
