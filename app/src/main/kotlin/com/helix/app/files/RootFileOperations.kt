package com.helix.app.files

import java.io.File

/**
 * Seam for root filesystem operations in the manual File Manager.
 * Implemented in developer flavor using libsu; no-op in consumer flavor.
 * This is strictly a user-operated storage backend; never registered as an Agent capability or tool.
 */
internal interface RootFileOperations {
    /** True if root file operations are supported in this flavor. */
    val isSupported: Boolean

    /** True if root permission is currently granted to the app. */
    fun isRootGranted(): Boolean

    /**
     * Explicit user action: request root permission from the system.
     * Returns true if granted.
     */
    fun requestRoot(): Boolean

    /**
     * Lists immediate children of [relativePath] (where "" represents "/").
     */
    fun list(relativePath: String): List<FileManagerService.FileEntry>

    /**
     * Retrieves file metadata for [relativePath].
     */
    fun stat(relativePath: String): ManualFileInfo?

    /**
     * Reads up to [maxBytes] bytes from [relativePath] as text.
     */
    fun previewText(
        relativePath: String,
        maxBytes: Long,
    ): String?

    /**
     * Reads image bytes up to [maxBytes] from [relativePath].
     */
    fun previewImageBytes(
        relativePath: String,
        maxBytes: Long,
    ): ByteArray

    /**
     * Probes the MIME type of [relativePath].
     */
    fun mimeTypeFor(relativePath: String): String

    /**
     * Retrieves full file info (size, mtime, MIME, sha256).
     */
    fun fileInfo(
        relativePath: String,
        maxHashBytes: Long,
    ): FileManagerService.FileMeta

    /**
     * Stages a file from the root filesystem into an app-private cache file for sharing.
     */
    fun realFileFor(
        relativePath: String,
        shareDir: File,
    ): File

    /**
     * Manual mutation backend for the root filesystem, or null if mutations are not supported.
     */
    fun manualBackend(): ManualFileBackend?
}
