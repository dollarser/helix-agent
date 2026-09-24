package com.helix.app.root

import android.content.Context
import com.helix.app.files.FileManagerService
import com.helix.app.files.ManualFileBackend
import com.helix.app.files.ManualFileInfo
import com.helix.app.files.RootFileOperations
import java.io.File
import java.io.FileNotFoundException

/**
 * Consumer distribution has no libsu dependency and cannot access the root filesystem.
 */
internal object RootFileModule {
    @Suppress("UnusedParameter")
    fun create(context: Context): RootFileOperations = NoOpRootFileOperations

    private object NoOpRootFileOperations : RootFileOperations {
        override val isSupported: Boolean = false

        override fun isRootGranted(): Boolean = false

        override fun requestRoot(): Boolean = false

        override fun list(relativePath: String): List<FileManagerService.FileEntry> = emptyList()

        override fun stat(relativePath: String): ManualFileInfo? = null

        override fun previewText(
            relativePath: String,
            maxBytes: Long,
        ): String? = null

        override fun previewImageBytes(
            relativePath: String,
            maxBytes: Long,
        ): ByteArray = ByteArray(0)

        override fun mimeTypeFor(relativePath: String): String = "application/octet-stream"

        override fun fileInfo(
            relativePath: String,
            maxHashBytes: Long,
        ): FileManagerService.FileMeta =
            FileManagerService.FileMeta(-1L, -1L, "application/octet-stream", false, null, false)

        override fun realFileFor(
            relativePath: String,
            shareDir: File,
        ): File = throw FileNotFoundException("Root is not available in consumer build")

        override fun manualBackend(): ManualFileBackend? = null
    }
}
