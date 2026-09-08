package com.helix.app.proot

import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.core.workspace.AtomicFileWriter
import com.helix.runtime.proot.core.JobArchiveLimits
import com.helix.runtime.proot.core.ZipJobExtractor
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobRecordCodec
import com.helix.runtime.proot.ipc.ProotJobState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.InputStream
import java.nio.file.Files

/** Stores verified archives as session-owned artifacts. Never acknowledges or executes a result. */
internal class ProotResultStore(
    private val storage: HelixStorage,
    private val workspace: File,
    private val scratch: File,
) {
    fun persist(
        turnId: String,
        callId: String,
        record: ProotJobRecord,
        input: InputStream,
    ): File {
        val binding = binding(turnId, callId)
        check(binding.getValue("jobId").jsonPrimitive.content == record.jobId)
        check(binding.getValue("executionId").jsonPrimitive.content == record.executionId)
        check(binding.getValue("inputManifestSha256").jsonPrimitive.content == record.inputManifestSha256)
        check(record.state == ProotJobState.SUCCEEDED && !record.evidenceExpired)
        check(scratch.mkdirs() || scratch.isDirectory)
        val staging = Files.createTempDirectory(scratch.toPath(), "proot-result-").toFile()
        try {
            val candidate = File(staging, "result.zip")
            copyBounded(input, candidate)
            val extracted = ZipJobExtractor.extract(candidate, File(staging, "verified"))
            check(extracted.manifestSha256 == record.outputManifestSha256)
            publish(turnId, callId, record.jobId, candidate)
            return requireNotNull(readLocal(turnId, callId))
        } finally {
            staging.deleteRecursively()
        }
    }

    fun readLocal(
        turnId: String,
        callId: String,
    ): File? {
        val job = binding(turnId, callId).getValue("jobId").jsonPrimitive.content
        val session = storage.turns.resolve(turnId).sessionId
        val relative = relativePath(job)
        val artifact = storage.artifacts.findBySessionAndPath(session, relative) ?: return null
        check(artifact.id == "proot-result-$callId" && artifact.mediaType == "application/zip")
        val file = File(workspace, relative)
        check(artifact.size in 1..JobArchiveLimits.MAX_TOTAL_BYTES)
        check(file.isFile && file.length() == artifact.size)
        check(FileContentStore.sha256Hex(file) == artifact.sha256)
        return file
    }

    private fun publish(
        turnId: String,
        callId: String,
        job: String,
        candidate: File,
    ) {
        val session = storage.turns.resolve(turnId).sessionId
        val relative = relativePath(job)
        val hash = FileContentStore.sha256Hex(candidate)
        storage.withTransaction {
            val existing = storage.artifacts.findBySessionAndPath(session, relative)
            if (existing == null) {
                val target = File(workspace, relative)
                val parent = requireNotNull(target.parentFile)
                check(parent.mkdirs() || parent.isDirectory)
                AtomicFileWriter.writeAtomicStream(target.toPath()) { output ->
                    candidate.inputStream().use { it.copyTo(output) }
                }
                storage.artifacts.register(
                    "proot-result-$callId",
                    session,
                    relative,
                    "application/zip",
                    candidate.length(),
                    hash,
                    target,
                )
            } else {
                check(existing.id == "proot-result-$callId")
                check(existing.sha256 == hash && existing.size == candidate.length())
            }
        }
    }

    private fun binding(
        turnId: String,
        callId: String,
    ): JsonObject {
        val call = requireNotNull(storage.toolCalls.byTurnAndCallId(turnId, callId))
        check(call.name in setOf("bash", "code.linux.run"))
        val binding = ProotJobBindingStore(storage).resolve(callId)
        check(binding.getValue("turnId").jsonPrimitive.content == turnId)
        return binding
    }

    private fun relativePath(job: String): String {
        ProotJobRecordCodec.checkJobId(job)
        return ".helix/proot-results/$job.zip"
    }

    private fun copyBounded(
        input: InputStream,
        file: File,
    ) {
        file.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            var count = input.read(buffer)
            while (count != -1) {
                total += count
                check(total <= JobArchiveLimits.MAX_TOTAL_BYTES) { "result archive exceeds size cap" }
                output.write(buffer, 0, count)
                count = input.read(buffer)
            }
        }
    }
}
