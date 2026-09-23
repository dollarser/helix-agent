package com.helix.core.storage.content

import com.helix.core.storage.HelixDatabase
import java.io.File

/**
 * Result metrics for storage-level garbage collection.
 */
data class StorageGcResult(
    val deletedContentFiles: Int,
    val deletedTempFiles: Int,
    val freedBytes: Long,
    val scannedFiles: Int,
)

fun interface ContentReferenceChecker {
    fun isReferenced(refString: String): Boolean
}

/**
 * Lightweight garbage collector for orphaned content-addressed bodies and abandoned
 * write-ahead temporary files (HXA maintenance).
 *
 * Safety principles:
 * - Read-only inspection of physical files before any action;
 * - Grace period protection: files modified within [gracePeriodMillis] are never touched,
 *   preventing race conditions against concurrent writes and uncommitted transactions;
 * - Strict reference check against all Room tables referencing ContentRef;
 * - Fail-safe and bounded: file deletion exceptions are caught and never crash callers.
 */
object StorageGarbageCollector {
    const val DEFAULT_GRACE_PERIOD_MS: Long = 3600_000L // 1 hour

    fun collectGarbage(
        contentRoot: File,
        database: HelixDatabase,
        gracePeriodMillis: Long = DEFAULT_GRACE_PERIOD_MS,
        now: Long = System.currentTimeMillis(),
    ): StorageGcResult =
        collectGarbage(
            contentRoot = contentRoot,
            referenceChecker = { refString ->
                database.messageDao().countByContentRef(refString) > 0 ||
                    database.toolResultDao().countByContentRef(refString) > 0 ||
                    database.sessionInputDao().countByContentRef(refString) > 0
            },
            gracePeriodMillis = gracePeriodMillis,
            now = now,
        )

    fun collectGarbage(
        contentRoot: File,
        referenceChecker: ContentReferenceChecker,
        gracePeriodMillis: Long = DEFAULT_GRACE_PERIOD_MS,
        now: Long = System.currentTimeMillis(),
    ): StorageGcResult {
        if (!contentRoot.exists() || !contentRoot.isDirectory) {
            return StorageGcResult(0, 0, 0L, 0)
        }

        var deletedContent = 0
        var deletedTemp = 0
        var freedBytes = 0L
        var scanned = 0

        val contentDir = File(contentRoot, "content").let { if (it.exists()) it else contentRoot }
        val allFiles = runCatching { contentDir.walkTopDown().filter { it.isFile }.toList() }.getOrDefault(emptyList())

        for (file in allFiles) {
            scanned++
            val lastModified = runCatching { file.lastModified() }.getOrDefault(now)
            if (now - lastModified < gracePeriodMillis) continue

            val outcome = processCandidate(file, referenceChecker)
            if (outcome.deletedTemp) {
                deletedTemp++
                freedBytes += outcome.freedBytes
            } else if (outcome.deletedContent) {
                deletedContent++
                freedBytes += outcome.freedBytes
            }
        }

        return StorageGcResult(
            deletedContentFiles = deletedContent,
            deletedTempFiles = deletedTemp,
            freedBytes = freedBytes,
            scannedFiles = scanned,
        )
    }

    private data class FileGcOutcome(
        val deletedTemp: Boolean = false,
        val deletedContent: Boolean = false,
        val freedBytes: Long = 0L,
    )

    private fun processCandidate(
        file: File,
        referenceChecker: ContentReferenceChecker,
    ): FileGcOutcome {
        val fileName = file.name
        var outcome = FileGcOutcome()
        if (isTempFile(fileName)) {
            val size = runCatching { file.length() }.getOrDefault(0L)
            if (runCatching { file.delete() }.getOrDefault(false)) {
                cleanEmptyParent(file)
                outcome = FileGcOutcome(deletedTemp = true, freedBytes = size)
            }
        } else if (isContentFile(file)) {
            val sha256 = fileName
            val size = runCatching { file.length() }.getOrDefault(0L)
            val relPath = ContentRef.expectedPath(sha256)
            val refString = ContentRef(relPath, size, sha256).toStorageString()
            val isReferenced = runCatching { referenceChecker.isReferenced(refString) }.getOrDefault(true)

            if (!isReferenced && runCatching { file.delete() }.getOrDefault(false)) {
                cleanEmptyParent(file)
                outcome = FileGcOutcome(deletedContent = true, freedBytes = size)
            }
        }
        return outcome
    }

    private fun isTempFile(name: String): Boolean = name.contains(".tmp-") || name.endsWith(".tmp")

    private fun isContentFile(file: File): Boolean {
        val name = file.name
        val parent = file.parentFile
        return name.length == 64 &&
            name.all { it in "0123456789abcdef" } &&
            parent != null &&
            parent.name == name.substring(0, 2)
    }

    private fun cleanEmptyParent(file: File) {
        val parent = file.parentFile ?: return
        val children = runCatching { parent.list() }.getOrNull()
        if (children != null && children.isEmpty()) {
            runCatching { parent.delete() }
        }
    }
}
