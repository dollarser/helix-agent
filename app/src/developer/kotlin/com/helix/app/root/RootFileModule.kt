package com.helix.app.root

import android.content.Context
import com.helix.app.files.FileManagerService
import com.helix.app.files.ManualFileBackend
import com.helix.app.files.ManualFileInfo
import com.helix.app.files.RootFileOperations
import com.helix.core.workspace.ContentProbe
import com.helix.tools.root.RootFileAccessor
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Developer distribution implementation of [RootFileOperations] using [RootFileAccessor].
 * Provides full filesystem access for manual user operations in the File Manager.
 */
internal object RootFileModule {
    fun create(context: Context): RootFileOperations = RealRootFileOperations(context.applicationContext)

    private class RealRootFileOperations(
        private val context: Context,
    ) : RootFileOperations {
        override val isSupported: Boolean = true

        override fun isRootGranted(): Boolean = RootFileAccessor.isRootGranted()

        override fun requestRoot(): Boolean = RootFileAccessor.requestRoot()

        override fun list(relativePath: String): List<FileManagerService.FileEntry> {
            val targetPath = RootFileAccessor.toAbsolutePath(relativePath)
            val entries = RootFileAccessor.list(targetPath)
            return entries.map { entry ->
                val childRel = if (relativePath.isEmpty()) entry.name else "$relativePath/${entry.name}"
                FileManagerService.FileEntry(
                    name = entry.name,
                    relativePath = childRel,
                    isDirectory = entry.isDirectory,
                    sizeBytes = entry.sizeBytes,
                    mtimeEpochMillis = entry.mtimeEpochMillis,
                )
            }
        }

        override fun stat(relativePath: String): ManualFileInfo? {
            val targetPath = RootFileAccessor.toAbsolutePath(relativePath)
            val stat = RootFileAccessor.stat(targetPath) ?: return null
            return ManualFileInfo(stat.isDirectory, stat.sizeBytes)
        }

        @Suppress("ReturnCount")
        override fun previewText(
            relativePath: String,
            maxBytes: Long,
        ): String? {
            val targetPath = RootFileAccessor.toAbsolutePath(relativePath)
            val cap = minOf(maxBytes, 64L * 1024)
            val bytes = RootFileAccessor.readBytes(targetPath, cap) ?: return null
            if (!ContentProbe.probeBytes(bytes, bytes.size.toLong()).isText) return null
            return String(bytes, Charsets.UTF_8)
        }

        @Suppress("ReturnCount")
        override fun previewImageBytes(
            relativePath: String,
            maxBytes: Long,
        ): ByteArray {
            val targetPath = RootFileAccessor.toAbsolutePath(relativePath)
            val cap = minOf(maxBytes, 4L * 1024 * 1024)
            val bytes = RootFileAccessor.readBytes(targetPath, cap) ?: return ByteArray(0)
            if (!ContentProbe.probeBytes(bytes, bytes.size.toLong()).mimeType.startsWith("image/")) {
                return ByteArray(0)
            }
            return bytes
        }

        override fun mimeTypeFor(relativePath: String): String {
            val targetPath = RootFileAccessor.toAbsolutePath(relativePath)
            val bytes = RootFileAccessor.readBytes(targetPath, 512)
            if (bytes != null && bytes.isNotEmpty()) {
                return ContentProbe.probeBytes(bytes, bytes.size.toLong()).mimeType
            }
            return "application/octet-stream"
        }

        @Suppress("ReturnCount")
        override fun fileInfo(
            relativePath: String,
            maxHashBytes: Long,
        ): FileManagerService.FileMeta {
            val targetPath = RootFileAccessor.toAbsolutePath(relativePath)
            val stat =
                RootFileAccessor.stat(targetPath)
                    ?: return FileManagerService.FileMeta(-1L, -1L, "application/octet-stream", false, null, false)

            if (stat.isDirectory) {
                return FileManagerService.FileMeta(stat.sizeBytes, 0L, "inode/directory", true, null, true)
            }

            val size = stat.sizeBytes
            val prefix = if (size > 0) RootFileAccessor.readBytes(targetPath, 8192) else null
            val probe = if (prefix != null) ContentProbe.probeBytes(prefix, size) else null
            val mime = probe?.mimeType ?: "application/octet-stream"
            val isText = probe?.isText ?: false

            var sha: String? = null
            var omitted = false
            if (size in 1..maxHashBytes) {
                sha = RootFileAccessor.sha256(targetPath)
                if (sha == null) omitted = true
            } else if (size > maxHashBytes) {
                omitted = true
            }

            return FileManagerService.FileMeta(size, 0L, mime, isText, sha, omitted)
        }

        override fun realFileFor(
            relativePath: String,
            shareDir: File,
        ): File {
            if (!shareDir.exists()) shareDir.mkdirs()
            val target = File(shareDir, "share-${System.nanoTime()}.bin")
            val targetPath = RootFileAccessor.toAbsolutePath(relativePath)
            RootFileAccessor.copyFile(targetPath, target)
            return target
        }

        override fun manualBackend(): ManualFileBackend = RootManualBackend()

        private inner class RootManualBackend : ManualFileBackend {
            override fun validateMutation(path: String) {
                check(isRootGranted()) { "Root permission required" }
                val abs = RootFileAccessor.toAbsolutePath(path)
                require(abs != "/") { "The storage root cannot be changed" }
            }

            override fun stat(path: String): ManualFileInfo? = this@RealRootFileOperations.stat(path)

            override fun children(path: String): List<String> =
                this@RealRootFileOperations.list(path).map { it.name }.sorted()

            override fun read(path: String): InputStream {
                val targetPath = RootFileAccessor.toAbsolutePath(path)
                val bytes = RootFileAccessor.readAllBytes(targetPath)
                return ByteArrayInputStream(bytes)
            }

            override fun create(
                path: String,
                directory: Boolean,
            ) {
                validateMutation(path)
                val abs = RootFileAccessor.toAbsolutePath(path)
                RootFileAccessor.create(abs, directory)
            }

            override fun write(path: String): OutputStream {
                validateMutation(path)
                val abs = RootFileAccessor.toAbsolutePath(path)
                return object : ByteArrayOutputStream() {
                    override fun close() {
                        super.close()
                        RootFileAccessor.writeBytes(abs, toByteArray(), context.cacheDir)
                    }
                }
            }

            override fun rename(
                path: String,
                destination: String,
            ) {
                validateMutation(path)
                validateMutation(destination)
                val src = RootFileAccessor.toAbsolutePath(path)
                val dst = RootFileAccessor.toAbsolutePath(destination)
                RootFileAccessor.rename(src, dst)
            }

            override fun delete(path: String) {
                validateMutation(path)
                val abs = RootFileAccessor.toAbsolutePath(path)
                RootFileAccessor.delete(abs)
            }
        }
    }
}
