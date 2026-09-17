package com.helix.tools.root

import android.content.Context
import android.content.pm.PackageManager
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

internal class RootServiceOperations(
    // Nullable so the pure-JVM process-scan path can be exercised without an Android
    // context; operations that need the context fail closed when it is absent.
    private val context: Context?,
    private val procRoot: File = File("/proc"),
) {
    fun execute(request: RootOperationRequest): RootOperationResult =
        try {
            when (request) {
                is RootOperationRequest.FileRead -> readFile(request)
                is RootOperationRequest.PackageInfo -> packageInfo(request)
                is RootOperationRequest.ProcessList -> processList(request)
                is RootOperationRequest.LogRead -> logRead(request)
            }
        } catch (_: SecurityException) {
            RootOperationResult.Failed("ROOT_OPERATION_DENIED")
        } catch (_: Exception) {
            RootOperationResult.Failed("ROOT_OPERATION_FAILED")
        }

    private fun readFile(request: RootOperationRequest.FileRead): RootOperationResult {
        require(request.offset >= 0 && request.maxBytes in 1..RootTools.MAX_FILE_BYTES)
        val root = File(request.scopeRoot).canonicalFile
        val file = File(request.path).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "ROOT_PATH_ESCAPES_SCOPE" }
        require(file.isFile) { "ROOT_FILE_NOT_FOUND" }
        val size = file.length()
        require(request.offset <= size) { "ROOT_OFFSET_OUT_OF_RANGE" }
        val count = minOf(request.maxBytes.toLong(), size - request.offset).toInt()
        val bytes = ByteArray(count)
        RandomAccessFile(file, "r").use { input ->
            input.seek(request.offset)
            input.readFully(bytes)
        }
        return RootOperationResult.File(RootFileChunk(bytes, request.offset, size, request.offset + count >= size))
    }

    private fun packageInfo(request: RootOperationRequest.PackageInfo): RootOperationResult {
        require(PACKAGE_NAME.matches(request.packageName)) { "ROOT_PACKAGE_INVALID" }
        val packageManager = context?.packageManager ?: return RootOperationResult.Failed("ROOT_CONTEXT_MISSING")
        val info = packageManager.getPackageInfo(request.packageName, 0)
        val app = requireNotNull(info.applicationInfo)
        return RootOperationResult.Package(
            RootPackageRecord(request.packageName, app.uid, app.sourceDir.orEmpty(), info.versionName),
        )
    }

    private fun processList(request: RootOperationRequest.ProcessList): RootOperationResult {
        require(request.limit in 1..RootTools.MAX_PROCESSES)
        val records =
            procRoot
                .listFiles()
                .orEmpty()
                .asSequence()
                .filter { it.name.all(Char::isDigit) }
                .mapNotNull(::readProcess)
                .sortedBy { it.pid }
                .take(request.limit)
                .toList()
        return RootOperationResult.Processes(records)
    }

    // A process can die between the /proc listing and the per-entry read (or the entry can be
    // malformed); racing or unreadable entries are skipped individually so a live scan never
    // fails the whole operation.
    private fun readProcess(dir: File): RootProcessRecord? =
        try {
            val pid = dir.name.toIntOrNull()
            readProcessRecord(dir, pid)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }

    private fun readProcessRecord(
        dir: File,
        pid: Int?,
    ): RootProcessRecord? {
        val status = File(dir, "status").readLines().take(64)
        val name =
            status
                .firstOrNull { it.startsWith("Name:\t") }
                ?.substringAfter('\t')
                ?.take(256)
        val uid =
            status
                .firstOrNull { it.startsWith("Uid:\t") }
                ?.substringAfter('\t')
                ?.substringBefore('\t')
                ?.toIntOrNull()
        return if (pid != null && uid != null && name != null) RootProcessRecord(pid, uid, name) else null
    }

    private fun logRead(request: RootOperationRequest.LogRead): RootOperationResult {
        require(request.maxLines in 1..RootTools.MAX_LOG_LINES)
        require(request.minPriority in PRIORITIES)
        val process =
            ProcessBuilder(
                "/system/bin/logcat",
                "-d",
                "-t",
                request.maxLines.toString(),
                "*:" + request.minPriority,
            ).redirectErrorStream(true)
                .start()
        if (!process.waitFor(LOGCAT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return RootOperationResult.Failed("ROOT_LOG_TIMEOUT")
        }
        val lines =
            process.inputStream.bufferedReader().useLines { sequence ->
                sequence.take(request.maxLines).map { it.take(RootTools.MAX_LOG_LINE_LENGTH) }.toList()
            }
        return RootOperationResult.Logs(lines)
    }

    private companion object {
        val PACKAGE_NAME = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){0,50}")
        val PRIORITIES = setOf("V", "D", "I", "W", "E", "F", "S")
        const val LOGCAT_TIMEOUT_SECONDS = 5L
    }
}
