package com.helix.tools.root

data class RootFileChunk(
    val bytes: ByteArray,
    val offset: Long,
    val sizeBytes: Long,
    val eof: Boolean,
)

data class RootPackageRecord(
    val packageName: String,
    val uid: Int,
    val sourceDir: String,
    val versionName: String?,
)

data class RootProcessRecord(
    val pid: Int,
    val uid: Int,
    val name: String,
)

sealed interface RootOperationRequest {
    data class FileRead(
        val scopeRoot: String,
        val path: String,
        val offset: Long,
        val maxBytes: Int,
    ) : RootOperationRequest

    data class PackageInfo(
        val packageName: String,
    ) : RootOperationRequest

    data class ProcessList(
        val limit: Int,
    ) : RootOperationRequest

    data class LogRead(
        val maxLines: Int,
        val minPriority: String,
    ) : RootOperationRequest
}

sealed interface RootOperationResult {
    data class File(
        val chunk: RootFileChunk,
    ) : RootOperationResult

    data class Package(
        val record: RootPackageRecord,
    ) : RootOperationResult

    data class Processes(
        val records: List<RootProcessRecord>,
    ) : RootOperationResult

    data class Logs(
        val lines: List<String>,
    ) : RootOperationResult

    data class Failed(
        val code: String,
    ) : RootOperationResult
}

/** Typed RootService boundary: it deliberately has no arbitrary command or secret field. */
interface RootOperationPort {
    fun status(): RootAccessStatus

    fun execute(request: RootOperationRequest): RootOperationResult
}
