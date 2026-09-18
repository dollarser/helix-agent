package com.helix.runtime.cli.app

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

@Suppress("TooManyFunctions") // Request, streaming output, publication and cleanup share one payload owner.
internal class CodexPayloadFiles(
    private val jobs: File,
) {
    fun putRequest(
        jobId: String,
        bytes: ByteArray,
    ) = atomicWrite(file(jobId, REQUEST), bytes)

    fun loadRequest(jobId: String): ByteArray =
        file(jobId, REQUEST).readBytes().also {
            require(it.isNotEmpty())
        }

    fun putOutput(
        jobId: String,
        bytes: ByteArray,
    ) = atomicWrite(file(jobId, OUTPUT), bytes)

    fun writeOutput(
        jobId: String,
        publish: (() -> Unit) -> Unit,
        write: (OutputStream) -> Unit,
    ) = atomicWrite(file(jobId, OUTPUT), publish, write)

    fun openOutput(jobId: String): java.io.FileInputStream? = file(jobId, OUTPUT).takeIf(File::isFile)?.inputStream()

    fun loadOutput(jobId: String): ByteArray? =
        file(jobId, OUTPUT).takeIf(File::isFile)?.readBytes()?.also {
            require(it.isNotEmpty())
        }

    fun delete(jobId: String) {
        requireDeleted(file(jobId, REQUEST))
        requireDeleted(file(jobId, OUTPUT))
        requireDeleted(file(jobId, "$REQUEST.tmp"))
        requireDeleted(file(jobId, "$OUTPUT.tmp"))
    }

    /** Reject malformed payload paths before committing the acknowledgement. */
    fun validateCleanup(jobId: String) {
        listOf(REQUEST, OUTPUT, "$REQUEST.tmp", "$OUTPUT.tmp").forEach { name ->
            val target = file(jobId, name)
            require(!target.exists() || target.isFile) { "payload cleanup path is not a file" }
        }
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
    ) {
        require(bytes.isNotEmpty())
        atomicWrite(target) { it.write(bytes) }
    }

    private fun atomicWrite(
        target: File,
        publish: (() -> Unit) -> Unit = { it() },
        write: (OutputStream) -> Unit,
    ) {
        require(target.parentFile?.mkdirs() == true || target.parentFile?.isDirectory == true)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(tmp).use { out ->
                write(out)
                out.flush()
                out.fd.sync()
            }
            publish {
                require(tmp.length() > 0)
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (tmp.exists()) check(tmp.delete()) { "failed to remove unpublished payload" }
        }
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
