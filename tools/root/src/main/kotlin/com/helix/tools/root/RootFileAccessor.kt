package com.helix.tools.root

import android.util.Base64
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

data class RootEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val mtimeEpochMillis: Long,
)

data class RootStat(
    val isDirectory: Boolean,
    val sizeBytes: Long,
)

/**
 * Shell-backed filesystem operations for the manual file manager in developer builds.
 * All operations execute with root privileges when granted.
 */
@Suppress("TooManyFunctions")
object RootFileAccessor {
    init {
        Shell.setDefaultBuilder(
            Shell.Builder
                .create()
                .setFlags(Shell.FLAG_MOUNT_MASTER),
        )
    }

    fun isRootGranted(): Boolean = Shell.isAppGrantedRoot() == true

    fun requestRoot(): Boolean =
        try {
            Shell.setDefaultBuilder(
                Shell.Builder
                    .create()
                    .setFlags(Shell.FLAG_MOUNT_MASTER),
            )
            Shell.getShell().isRoot
        } catch (_: RuntimeException) {
            false
        }

    fun toAbsolutePath(relativePath: String): String =
        if (relativePath.isEmpty() || relativePath == "/") "/" else "/" + relativePath.trimStart('/')

    private fun resolvePath(path: String): String =
        when {
            path.trimEnd('/') == "/data/data" -> "/data/user/0"
            path.startsWith("/data/data/") -> "/data/user/0/" + path.removePrefix("/data/data/")
            else -> path
        }

    private fun escapeShell(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

    private data class RawEntry(
        val type: String,
        val size: Long,
        val mtime: Long,
        val fullPath: String,
        val name: String,
    )

    private fun parseRawEntries(lines: List<String>): Pair<List<RawEntry>, List<String>> {
        val rawList = mutableListOf<RawEntry>()
        val symlinks = mutableListOf<String>()
        for (line in lines) {
            val parts = line.split('|')
            if (parts.size < 4) continue
            val type = parts[0]
            val size = parts[1].toLongOrNull() ?: 0L
            val mtime = (parts[2].toLongOrNull() ?: 0L) * 1000L
            val fullPath = parts.subList(3, parts.size).joinToString("|")
            val name = fullPath.substringAfterLast('/')
            if (name.isNotEmpty()) {
                rawList.add(RawEntry(type, size, mtime, fullPath, name))
                if (type == "symbolic link") {
                    symlinks.add(fullPath)
                }
            }
        }
        return Pair(rawList, symlinks)
    }

    @Suppress("TooGenericExceptionCaught")
    fun list(targetPath: String): List<RootEntry> {
        val resolved = resolvePath(targetPath)
        val res =
            if (resolved.trimEnd('/') == "/storage/emulated") {
                val probeCmd =
                    "for d in /data/media/* /storage/emulated/0; do " +
                        "if [ -d \"\$d\" ]; then " +
                        "b=\$(basename \"\$d\"); " +
                        "target=\"/storage/emulated/\$b\"; " +
                        "if [ -d \"\$target\" ]; then " +
                        "stat -c \"%F|%s|%Y|%n\" \"\$target\"; " +
                        "fi; " +
                        "fi; " +
                        "done | sort -u"
                Shell.cmd(probeCmd).exec()
            } else {
                val escaped = escapeShell(resolved)
                Shell.cmd("find -H $escaped -maxdepth 1 -mindepth 1 -exec stat -c \"%F|%s|%Y|%n\" {} +").exec()
            }
        if (!res.isSuccess) {
            throw FileNotFoundException("Cannot list root directory: $targetPath")
        }

        val (rawList, symlinks) = parseRawEntries(res.out)

        val dirSymlinks =
            if (symlinks.isNotEmpty()) {
                val batchCmd =
                    symlinks.joinToString("; ") { p ->
                        "[ -d ${escapeShell(p)} ] && echo ${escapeShell(p)}"
                    }
                val probeRes: List<String> = Shell.cmd(batchCmd).exec().out
                probeRes.map { it.trim('\'') }.toSet()
            } else {
                emptySet()
            }

        return rawList.map { raw ->
            val isDir = raw.type.contains("directory") || raw.fullPath in dirSymlinks
            RootEntry(
                name = raw.name,
                isDirectory = isDir,
                sizeBytes = if (isDir) 0L else raw.size,
                mtimeEpochMillis = raw.mtime,
            )
        }
    }

    @Suppress("ReturnCount")
    fun stat(targetPath: String): RootStat? {
        val resolved = resolvePath(targetPath)
        val res = Shell.cmd("stat -c \"%F|%s\" " + escapeShell(resolved)).exec()
        val lines: List<String> = res.out
        if (!res.isSuccess || lines.isEmpty()) return null
        val parts = lines.first().split('|')
        if (parts.size < 2) return null
        val type = parts[0]
        val size = parts[1].toLongOrNull() ?: 0L
        val isDir =
            type.contains("directory") ||
                (type == "symbolic link" && Shell.cmd("[ -d " + escapeShell(resolved) + " ]").exec().isSuccess)
        return RootStat(isDir, if (isDir) 0L else size)
    }

    fun readBytes(
        targetPath: String,
        maxBytes: Long,
    ): ByteArray? {
        val resolved = resolvePath(targetPath)
        val res = Shell.cmd("head -c $maxBytes " + escapeShell(resolved) + " | base64").exec()
        val lines: List<String> = res.out
        if (!res.isSuccess || lines.isEmpty()) return null
        return try {
            Base64.decode(lines.joinToString(""), Base64.DEFAULT)
        } catch (_: Exception) {
            null
        }
    }

    fun readAllBytes(targetPath: String): ByteArray {
        val resolved = resolvePath(targetPath)
        val res = Shell.cmd("base64 " + escapeShell(resolved)).exec()
        check(res.isSuccess) { "Failed to read root file: $targetPath" }
        val lines: List<String> = res.out
        return Base64.decode(lines.joinToString(""), Base64.DEFAULT)
    }

    fun writeBytes(
        targetPath: String,
        bytes: ByteArray,
        cacheDir: File,
    ) {
        val resolved = resolvePath(targetPath)
        val tempFile = File(cacheDir, "root-write-${System.nanoTime()}.tmp")
        try {
            Files.write(tempFile.toPath(), bytes)
            val copyCmd =
                "cp " + escapeShell(tempFile.absolutePath) + " " + escapeShell(resolved) +
                    " && chmod 644 " + escapeShell(resolved)
            val res = Shell.cmd(copyCmd).exec()
            check(res.isSuccess) { "Failed to write root file: $targetPath" }
        } finally {
            tempFile.delete()
        }
    }

    fun create(
        targetPath: String,
        directory: Boolean,
    ) {
        val resolved = resolvePath(targetPath)
        val cmd = if (directory) "mkdir -p " + escapeShell(resolved) else "touch " + escapeShell(resolved)
        val res = Shell.cmd(cmd).exec()
        check(res.isSuccess) { "Failed to create root item: $targetPath" }
    }

    fun rename(
        srcPath: String,
        dstPath: String,
    ) {
        val resolvedSrc = resolvePath(srcPath)
        val resolvedDst = resolvePath(dstPath)
        val res = Shell.cmd("mv " + escapeShell(resolvedSrc) + " " + escapeShell(resolvedDst)).exec()
        check(res.isSuccess) { "Failed to rename: $srcPath to $dstPath" }
    }

    fun delete(targetPath: String) {
        val resolved = resolvePath(targetPath)
        val res = Shell.cmd("rm -rf " + escapeShell(resolved)).exec()
        check(res.isSuccess) { "Failed to delete: $targetPath" }
    }

    fun sha256(targetPath: String): String? {
        val resolved = resolvePath(targetPath)
        val shaRes = Shell.cmd("sha256sum " + escapeShell(resolved)).exec()
        val shaLines: List<String> = shaRes.out
        if (shaRes.isSuccess && shaLines.isNotEmpty()) {
            return shaLines.first().substringBefore(' ').trim()
        }
        return null
    }

    fun copyFile(
        srcPath: String,
        dstFile: File,
    ) {
        val resolved = resolvePath(srcPath)
        val copyCmd =
            "cp " + escapeShell(resolved) + " " + escapeShell(dstFile.absolutePath) +
                " && chmod 644 " + escapeShell(dstFile.absolutePath)
        val res = Shell.cmd(copyCmd).exec()
        if (!res.isSuccess || !dstFile.exists()) {
            throw FileNotFoundException("Failed to copy root file to: ${dstFile.absolutePath}")
        }
    }
}
