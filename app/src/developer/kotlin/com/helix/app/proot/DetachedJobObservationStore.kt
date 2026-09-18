package com.helix.app.proot

import com.helix.core.storage.HelixStorage
import com.helix.runtime.proot.ipc.DetachedJobBinding
import com.helix.runtime.proot.ipc.ProotJobRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Local display receipts only. Browsing these facts never connects to the Runtime. */
internal class DetachedJobObservationStore(
    private val storage: HelixStorage,
) {
    fun observe(
        binding: DetachedJobBinding,
        record: ProotJobRecord,
    ) {
        check(binding.jobId == record.jobId && binding.executionId == record.executionId)
        check(binding.inputManifestSha256 == record.inputManifestSha256)
        if (!record.state.isTerminal) return
        val payload =
            buildJsonObject {
                put("version", 1)
                put("jobId", record.jobId)
                put("terminalCommit", requireNotNull(record.terminalCommit))
                put("state", record.state.wire)
                put("exitCode", record.exitCode)
            }.toString()
        appendOnce(binding, "terminal", payload)
    }

    /** Written only after imports, budget settlement and ownership release have completed. */
    fun settled(
        binding: DetachedJobBinding,
        record: ProotJobRecord,
    ) {
        observe(binding, record)
        appendOnce(binding, "settled", requireNotNull(record.terminalCommit))
    }

    fun read(binding: DetachedJobBinding): DetachedCommandFacts? {
        val terminal = event(binding, "terminal") ?: return null
        val payload = Json.parseToJsonElement(terminal.redactedPayload).jsonObject
        check(payload.getValue("version").jsonPrimitive.content == "1")
        check(payload.getValue("jobId").jsonPrimitive.content == binding.jobId)
        val settled = event(binding, "settled")
        check(settled == null || settled.redactedPayload == payload.getValue("terminalCommit").jsonPrimitive.content)
        return DetachedCommandFacts(
            payload.getValue("state").jsonPrimitive.content,
            payload["exitCode"]?.jsonPrimitive?.intOrNull,
            settled != null,
        )
    }

    private fun appendOnce(
        binding: DetachedJobBinding,
        kind: String,
        payload: String,
    ) {
        storage.withTransaction {
            val existing = event(binding, kind)
            if (existing == null) {
                storage.auditEvents.append(
                    id(binding, kind),
                    binding.sessionId,
                    "proot.job_$kind",
                    "platform",
                    payload,
                    System.currentTimeMillis(),
                )
            } else {
                check(existing.redactedPayload == payload) { "Original Job observation changed" }
            }
        }
    }

    private fun event(
        binding: DetachedJobBinding,
        kind: String,
    ) = try {
        storage.auditEvents.resolve(id(binding, kind)).also {
            check(it.correlationId == binding.sessionId && it.type == "proot.job_$kind")
        }
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun id(
        binding: DetachedJobBinding,
        kind: String,
    ) = "proot-$kind-${binding.toolCallId}"
}
