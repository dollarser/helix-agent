package com.helix.app.proot

import com.helix.app.APP_SCOPE_ID
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.core.workspace.AtomicFileWriter
import com.helix.core.workspace.FileScopePath
import com.helix.runtime.proot.core.JobArchiveLimits
import com.helix.runtime.proot.core.ZipJobExtractor
import com.helix.runtime.proot.ipc.ProotJobRecord
import com.helix.runtime.proot.ipc.ProotJobRecordCodec
import com.helix.runtime.proot.ipc.ProotJobState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
        check(record.state.isTerminal && record.outputManifestSha256 != null && !record.evidenceExpired)
        check(scratch.mkdirs() || scratch.isDirectory)
        val staging = Files.createTempDirectory(scratch.toPath(), "proot-result-").toFile()
        try {
            val candidate = File(staging, "result.zip")
            copyBounded(input, candidate)
            val extracted = ZipJobExtractor.extract(candidate, File(staging, "verified"))
            check(extracted.manifestSha256 == record.outputManifestSha256)
            publish(turnId, callId, record.jobId, candidate)
            saveRecord(turnId, callId, record)
            if (record.state != ProotJobState.SUCCEEDED) return requireNotNull(readLocal(turnId, callId))
            if (binding["workspacePath"]?.jsonPrimitive?.content != null &&
                binding["workspacePath"]?.jsonPrimitive?.content != "null"
            ) {
                ProotLiveArtifacts.register(storage, binding, turnId, callId, File(staging, "verified"))
            }
            val session = storage.turns.resolve(turnId).sessionId
            ProotProducedFiles.publish(
                workspace,
                File(staging, "verified"),
                record.jobId,
                extracted.manifest.entries,
                beforePublish = { ProotProducedAuthorization(storage).checkBeforePublish(turnId, callId) },
            ) { output ->
                val identity = (callId + "\u0000" + output.relative).toByteArray(Charsets.UTF_8)
                storage.withTransaction {
                    storage.artifacts.registerOrRefresh(
                        "proot-file-${LinuxRunTool.sha256Hex(identity)}",
                        session,
                        modelRef(output.relative),
                        output.mime,
                        output.size,
                        output.sha256,
                        turnId,
                        output.file,
                    )
                }
            }
            return requireNotNull(readLocal(turnId, callId))
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun saveRecord(
        turnId: String,
        callId: String,
        record: ProotJobRecord,
    ) {
        val id = "proot-result-record-$callId"
        storage.withTransaction {
            val existing = savedRecord(turnId, callId)
            if (existing == null) {
                storage.auditEvents.append(
                    id,
                    storage.turns.resolve(turnId).sessionId,
                    "proot.result_saved",
                    "platform",
                    ProotJobRecordCodec.encode(record),
                    System.currentTimeMillis(),
                )
            } else {
                check(existing.terminalCommit == record.terminalCommit)
            }
        }
    }

    fun savedRecord(
        turnId: String,
        callId: String,
    ): ProotJobRecord? {
        val original = binding(turnId, callId)
        val event =
            try {
                storage.auditEvents.resolve("proot-result-record-$callId")
            } catch (
                _: IllegalArgumentException,
            ) {
                return null
            }
        val record = ProotJobRecordCodec.parse(event.redactedPayload)
        check(record.jobId == original.getValue("jobId").jsonPrimitive.content)
        check(record.executionId == original.getValue("executionId").jsonPrimitive.content)
        check(record.inputManifestSha256 == original.getValue("inputManifestSha256").jsonPrimitive.content)
        return record
    }

    fun readLocal(
        turnId: String,
        callId: String,
    ): File? {
        val job = binding(turnId, callId).getValue("jobId").jsonPrimitive.content
        val session = storage.turns.resolve(turnId).sessionId
        val relative = relativePath(job)
        val artifact = storage.artifacts.findBySessionAndPath(session, modelRef(relative)) ?: return null
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
            val existing = storage.artifacts.findBySessionAndPath(session, modelRef(relative))
            if (existing == null) {
                val target = File(workspace, relative)
                val parent = requireNotNull(target.parentFile)
                check(parent.mkdirs() || parent.isDirectory)
                AtomicFileWriter.writeAtomicStream(target.toPath()) { output ->
                    candidate.inputStream().use { it.copyTo(output) }
                }
                storage.artifacts.registerOrRefresh(
                    "proot-result-$callId",
                    session,
                    modelRef(relative),
                    "application/zip",
                    candidate.length(),
                    hash,
                    turnId,
                    target,
                    purpose = "RUNTIME_EVIDENCE",
                )
            } else {
                check(existing.id == "proot-result-$callId")
                check(existing.sha256 == hash && existing.size == candidate.length())
            }
        }
    }

    @Suppress("ReturnCount") // Missing local evidence and live/snapshot results have distinct truthful outcomes.
    fun produced(
        turnId: String,
        callId: String,
    ): JsonObject {
        val original = binding(turnId, callId)
        val live = original["workspacePath"]?.jsonPrimitive?.contentOrNull != null
        if (live) {
            val archive =
                readLocal(turnId, callId)
                    ?: return ProotLiveArtifacts.registeredSummary(storage, original, turnId, emptyList())
            check(scratch.mkdirs() || scratch.isDirectory)
            val temporary = Files.createTempDirectory(scratch.toPath(), "live-result-").toFile()
            try {
                ZipJobExtractor.extract(archive, temporary)
                return ProotLiveArtifacts.registeredSummary(
                    storage,
                    original,
                    turnId,
                    ProotLiveArtifacts.entries(temporary),
                )
            } finally {
                temporary.deleteRecursively()
            }
        }
        val job = original.getValue("jobId").jsonPrimitive.content
        val session = storage.turns.resolve(turnId).sessionId
        val prefix = modelRef(ProotProducedFiles.relativeDirectory(job)) + "/"
        val entries =
            storage.artifacts
                .listBySession(session)
                .filter { it.turnId == turnId && it.relativePath.startsWith(prefix) }
                .map {
                    com.helix.runtime.proot.core.JobManifestEntry(
                        "output/" + it.relativePath.removePrefix(prefix),
                        it.sha256,
                        it.size,
                    )
                }
        return ProotArtifactReferences.summary(job, entries)
    }

    private fun binding(
        turnId: String,
        callId: String,
    ): JsonObject {
        val call = requireNotNull(storage.toolCalls.byTurnAndCallId(turnId, callId))
        check(call.name in setOf("bash", "code.linux.run", DetachedJobTools.START))
        val binding = ProotJobBindingStore(storage).resolve(callId)
        check(binding.getValue("turnId").jsonPrimitive.content == turnId)
        return binding
    }

    private fun relativePath(job: String): String {
        ProotJobRecordCodec.checkJobId(job)
        return ".helix/proot-results/$job.zip"
    }

    // The `artifacts` row stores the file's FULL `scope:` reference (the unique key and every
    // lookup agree on it); the I/O still uses the bare scope-relative path under this store's
    // app-scope workspace root.
    private fun modelRef(relative: String): String = FileScopePath(APP_SCOPE_ID, relative).toModelReference()

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
