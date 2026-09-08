package com.helix.runtime.cli.app

import com.helix.runtime.cli.client.CliModelEventCodec
import com.helix.runtime.cli.client.CliModelRequestCodec
import java.io.File
import java.io.FileOutputStream

internal class CodexPayloadFiles(
    private val jobs: File,
) {
    fun putRequest(
        jobId: String,
        bytes: ByteArray,
    ) = atomicWrite(file(jobId, REQUEST), bytes, CliModelRequestCodec.MAX_BYTES)

    fun loadRequest(jobId: String): ByteArray =
        file(jobId, REQUEST).readBytes().also {
            require(it.isNotEmpty() && it.size <= CliModelRequestCodec.MAX_BYTES)
        }

    fun putOutput(
        jobId: String,
        bytes: ByteArray,
    ) = atomicWrite(file(jobId, OUTPUT), bytes, CliModelEventCodec.MAX_BYTES)

    fun loadOutput(jobId: String): ByteArray? =
        file(jobId, OUTPUT).takeIf(File::isFile)?.readBytes()?.also {
            require(it.isNotEmpty() && it.size <= CliModelEventCodec.MAX_BYTES)
        }

    fun delete(jobId: String) {
        requireDeleted(file(jobId, REQUEST))
        requireDeleted(file(jobId, OUTPUT))
        requireDeleted(file(jobId, "$REQUEST.tmp"))
        requireDeleted(file(jobId, "$OUTPUT.tmp"))
    }

    fun payloadBytes(): Long =
        jobs
            .walkTopDown()
            .filter(File::isFile)
            .filter { it.name == REQUEST || it.name == OUTPUT }
            .sumOf(File::length)

    private fun atomicWrite(
        target: File,
        bytes: ByteArray,
        limit: Int,
    ) {
        require(bytes.isNotEmpty() && bytes.size <= limit)
        require(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        require(
            tmp.renameTo(target) ||
                runCatching {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }.isSuccess,
        )
    }

    private fun requireDeleted(target: File) {
        require(!target.exists() || target.delete()) { "failed to delete reconciled payload" }
    }

    private fun file(
        jobId: String,
        name: String,
    ) = File(File(jobs, jobId), name)

    companion object {
        private const val REQUEST = "request.json"
        private const val OUTPUT = "events.json"
    }
}
