package com.helix.app.provider

import com.helix.core.model.ModelEvent
import com.helix.core.storage.HelixStorage
import com.helix.core.storage.content.FileContentStore
import com.helix.core.workspace.AtomicFileWriter
import com.helix.runtime.cli.client.CliModelEventCodec
import com.helix.runtime.cli.client.CliModelJobRecord
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** Verified private result artifact survives process death and participates in session privacy deletion. */
internal class SubscriptionResultStore(
    private val storage: HelixStorage,
    private val workspace: File,
) {
    fun persist(
        ownership: LocalModelCallContext,
        record: CliModelJobRecord,
        events: List<ModelEvent>,
    ) {
        verifyBinding(ownership, record)
        val bytes = CliModelEventCodec.encode(events)
        check(FileContentStore.sha256Hex(bytes) == record.outputSha256)
        val relative = ".helix/subscription-results/${record.jobId}.json"
        val file = File(workspace, relative)
        check(file.parentFile.mkdirs() || file.parentFile.isDirectory)
        val hash = AtomicFileWriter.writeAtomic(file.toPath(), bytes)
        val session = storage.turns.resolve(ownership.turnId).sessionId
        storage.withTransaction {
            val existing = storage.artifacts.findBySessionAndPath(session, relative)
            if (existing == null) {
                storage.artifacts.register(
                    "cli-result-${ownership.modelCallId}",
                    session,
                    relative,
                    "application/json",
                    bytes.size.toLong(),
                    hash,
                    file,
                )
            } else {
                check(existing.sha256 == hash && existing.size == bytes.size.toLong())
            }
        }
        check(file.readBytes().contentEquals(bytes))
    }

    fun read(
        ownership: LocalModelCallContext,
        record: CliModelJobRecord,
    ): List<ModelEvent>? {
        verifyBinding(ownership, record)
        val session = storage.turns.resolve(ownership.turnId).sessionId
        val relative = ".helix/subscription-results/${record.jobId}.json"
        val artifact = storage.artifacts.findBySessionAndPath(session, relative) ?: return null
        check(artifact.id == "cli-result-${ownership.modelCallId}" && artifact.sha256 == record.outputSha256)
        return readLocal(ownership)
    }

    fun readLocal(ownership: LocalModelCallContext): List<ModelEvent>? {
        check(storage.modelCalls.resolve(ownership.modelCallId).turnId == ownership.turnId)
        val binding = SubscriptionJobBindingStore(storage).resolve(ownership.modelCallId)
        check(binding.getValue("turnId").jsonPrimitive.content == ownership.turnId)
        val job = binding.getValue("jobId").jsonPrimitive.content
        CliModelJobRecord.checkJobId(job)
        val session = storage.turns.resolve(ownership.turnId).sessionId
        val relative = ".helix/subscription-results/$job.json"
        val artifact = storage.artifacts.findBySessionAndPath(session, relative) ?: return null
        check(artifact.id == "cli-result-${ownership.modelCallId}")
        val file = File(workspace, relative)
        check(file.isFile && file.length() == artifact.size && artifact.size <= CliModelEventCodec.MAX_BYTES)
        val bytes = file.readBytes()
        check(FileContentStore.sha256Hex(bytes) == artifact.sha256)
        return CliModelEventCodec.decode(bytes)
    }

    private fun verifyBinding(
        ownership: LocalModelCallContext,
        record: CliModelJobRecord,
    ) {
        check(storage.modelCalls.resolve(ownership.modelCallId).turnId == ownership.turnId)
        val binding = SubscriptionJobBindingStore(storage).resolve(ownership.modelCallId)
        check(binding.getValue("turnId").jsonPrimitive.content == ownership.turnId)
        check(binding.getValue("jobId").jsonPrimitive.content == record.jobId)
        check(binding.getValue("requestSha256").jsonPrimitive.content == record.requestSha256)
    }
}
