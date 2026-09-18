package com.helix.runtime.proot.app

import android.util.AtomicFile
import com.helix.runtime.proot.core.DetachedLease
import com.helix.runtime.proot.ipc.DetachedJobBinding
import com.helix.runtime.proot.ipc.ProotJobCommand
import com.helix.runtime.proot.ipc.ProotJobSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/** Write-ahead ownership stays with the existing bounded journal and survives result collection. */
internal class DetachedJobStore(
    private val jobs: ProotJobStore,
) {
    data class Record(
        val binding: DetachedJobBinding,
        val lease: DetachedLease,
        val requestHash: String,
    )

    fun discardUnsubmitted(jobId: String) {
        if (jobs.load(jobId) != null) return
        val file = File(jobs.jobDir(jobId), FILE_NAME)
        AtomicFile(file).delete()
        file.parentFile?.delete() // Deletes only an empty directory, never job payloads.
    }

    fun read(jobId: String): Record? {
        val file = File(jobs.jobDir(jobId), FILE_NAME)
        if (!file.exists()) return null
        require(file.length() <= 4096)
        val obj = Json.parseToJsonElement(AtomicFile(file).openRead().bufferedReader().use { it.readText() }).jsonObject

        fun value(key: String) = obj.getValue(key).jsonPrimitive.content
        require(value("version") == "1")
        return Record(
            DetachedJobBinding(
                value("session"),
                value("turn"),
                value("call"),
                jobId,
                value("execution"),
                value("input"),
            ),
            DetachedLease(
                value("generation"),
                value("epoch").toLong(),
                value("elapsed").toLong(),
                value("duration").toLong(),
            ),
            value("requestHash"),
        )
    }

    @Suppress("TooGenericExceptionCaught") // AtomicFile rollback must happen for every failed write.
    fun write(record: Record) {
        val file = File(jobs.jobDir(record.binding.jobId), FILE_NAME)
        check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
        val text =
            buildJsonObject {
                put("version", 1)
                with(record.binding) {
                    put("session", sessionId)
                    put("turn", turnId)
                    put("call", toolCallId)
                    put("execution", executionId)
                    put("input", inputManifestSha256)
                }
                with(record.lease) {
                    put("generation", generation)
                    put("epoch", startedAtEpochMs)
                    put("elapsed", startedAtElapsedMs)
                    put("duration", durationMs)
                }
                put("requestHash", record.requestHash)
            }.toString()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray())
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            atomic.failWrite(output)
            throw failure
        }
    }

    companion object {
        const val FILE_NAME = "detached-owner.json"

        fun requestHash(spec: ProotJobSpec): String {
            val digest = MessageDigest.getInstance("SHA-256")

            fun field(value: String) {
                val bytes = value.toByteArray()
                digest.update("${bytes.size}:".toByteArray())
                digest.update(bytes)
            }
            when (val command = spec.command) {
                is ProotJobCommand.Argv -> {
                    field("argv")
                    field(command.arguments.size.toString())
                    command.arguments.forEach(::field)
                }

                is ProotJobCommand.Script -> {
                    field("script")
                    field(command.script)
                }
            }
            field("environment")
            field(spec.environment.size.toString())
            spec.environment.toSortedMap().forEach { (key, value) ->
                field(key)
                field(value)
            }
            listOf(
                spec.relativeWorkingDirectory,
                spec.stdinRelativePath.orEmpty(),
                spec.deadlineMs.toString(),
                spec.maxOutputBytes.toString(),
                spec.maxStderrBytes.toString(),
                spec.inputManifestSha256,
            ).forEach(::field)
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
