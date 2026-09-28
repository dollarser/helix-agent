package com.helix.app.storage

import android.content.Context
import com.helix.core.storage.HelixDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import kotlin.coroutines.coroutineContext

enum class StorageUsageCategory { RECORDS, WORKSPACES, MEMORY }

data class StorageUsageEntry(
    val category: StorageUsageCategory,
    val bytes: Long,
    val complete: Boolean,
)

/** User-requested metadata-only inventory. Never resolves bindings or follows external directories. */
class StorageUsageService internal constructor(
    private val roots: Map<StorageUsageCategory, List<Path>>,
) {
    constructor(context: Context) : this(
        mapOf(
            StorageUsageCategory.RECORDS to
                listOf(
                    context.getDatabasePath(HelixDatabase.DATABASE_NAME).toPath(),
                    context.getDatabasePath("${HelixDatabase.DATABASE_NAME}-wal").toPath(),
                    context.getDatabasePath("${HelixDatabase.DATABASE_NAME}-shm").toPath(),
                    context.getDatabasePath("${HelixDatabase.DATABASE_NAME}-journal").toPath(),
                    context.filesDir.resolve("helix-content").toPath(),
                ),
            StorageUsageCategory.WORKSPACES to
                listOf(
                    context.filesDir.resolve("workspaces").toPath(),
                    context.filesDir.resolve("workspace-metadata").toPath(),
                ),
            StorageUsageCategory.MEMORY to listOf(context.filesDir.resolve("memory").toPath()),
        ),
    )

    suspend fun snapshot(): List<StorageUsageEntry> =
        withContext(Dispatchers.IO) {
            val scanContext = coroutineContext
            roots.map { (category, paths) ->
                val usage = PrivateStorageScan.measure(paths) { scanContext.ensureActive() }
                StorageUsageEntry(category, usage.bytes, usage.complete)
            }
        }
}

internal data class PrivateStorageUsage(
    val bytes: Long,
    val complete: Boolean,
)

/** Bounded traversal reads metadata only and deliberately does not follow symbolic links. */
internal object PrivateStorageScan {
    fun measure(
        roots: List<Path>,
        maxEntries: Int = 10_000,
        checkCancelled: () -> Unit = {},
    ): PrivateStorageUsage {
        var bytes = 0L
        var visited = 0
        var complete = true
        val visitor =
            object : SimpleFileVisitor<Path>() {
                private fun admit(): Boolean {
                    checkCancelled()
                    visited++
                    if (visited > maxEntries) complete = false
                    return visited <= maxEntries
                }

                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult = if (admit()) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (!admit()) return FileVisitResult.TERMINATE
                    if (attrs.isRegularFile) bytes += attrs.size() else complete = false
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: java.io.IOException,
                ): FileVisitResult {
                    if (!admit()) return FileVisitResult.TERMINATE
                    // A missing top-level category has no files; a vanished child makes this scan partial.
                    if (exc !is NoSuchFileException || file !in roots) complete = false
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: java.io.IOException?,
                ): FileVisitResult {
                    checkCancelled()
                    if (exc != null) complete = false
                    return FileVisitResult.CONTINUE
                }
            }
        for (root in roots.distinct()) {
            checkCancelled()
            if (visited > maxEntries) break
            Files.walkFileTree(root, emptySet(), 64, visitor)
        }
        return PrivateStorageUsage(bytes, complete)
    }
}
