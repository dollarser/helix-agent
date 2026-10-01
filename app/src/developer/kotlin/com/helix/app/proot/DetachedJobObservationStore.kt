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
    ) = storage.withTransaction {
        check(binding.jobId == record.jobId && binding.executionId == record.executionId)
        check(binding.inputManifestSha256 == record.inputManifestSha256)
        if (event(binding, "disposed") != null) return@withTransaction
        // Do not publish a late RUNNING reply after a terminal receipt was committed.
        if (record.state.isTerminal || event(binding, "terminal") == null) recordObservation(binding, record, false)
        if (!record.state.isTerminal) return@withTransaction
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
    ) = storage.withTransaction {
        observe(binding, record)
        check(event(binding, "disposed") == null) { "Original Job was disposed as unknown" }
        appendOnce(binding, "settled", requireNotNull(record.terminalCommit))
        recordObservation(binding, record, true)
    }

    private fun recordObservation(
        binding: DetachedJobBinding,
        record: ProotJobRecord,
        settled: Boolean,
    ) {
        com.helix.app.chat.JobObservationJournal(storage).record(
            com.helix.tools.framework.JobObservation(
                observationBinding(binding),
                record.state.wire,
                record.state.isTerminal,
                record.state == com.helix.runtime.proot.ipc.ProotJobState.ORPHANED,
                !(settled || event(binding, "settled") != null),
                record.terminalCommit ?: record.state.wire,
                System.currentTimeMillis(),
                record.exitCode,
            ),
        )
    }

    private fun observationBinding(binding: DetachedJobBinding) =
        com.helix.tools.framework.JobObservationBinding(
            binding.sessionId,
            binding.turnId,
            binding.toolCallId,
            "android-proot",
            binding.executionId,
            binding.jobId,
            binding.inputManifestSha256,
        )

    fun read(binding: DetachedJobBinding): DetachedCommandFacts? {
        event(binding, "disposed")?.let {
            val payload = Json.parseToJsonElement(it.redactedPayload).jsonObject
            check(payload.getValue("executionId").jsonPrimitive.content == binding.executionId)
            check(payload.getValue("reason").jsonPrimitive.content == "RECORD_MISSING_AFTER_REBOOT")
            return DetachedCommandFacts("UNKNOWN", null, true)
        }
        return readTerminal(binding)
    }

    private fun readTerminal(binding: DetachedJobBinding): DetachedCommandFacts? {
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

    fun disposed(
        binding: DetachedJobBinding,
        boot: Int,
    ) {
        // Stable receipt does not change when a later retry runs after another reboot.
        storage.withTransaction {
            if (event(binding, "disposed") == null) {
                appendOnce(
                    binding,
                    "disposed",
                    buildJsonObject {
                        put("executionId", binding.executionId)
                        put("bootCount", boot)
                        put("reason", "RECORD_MISSING_AFTER_REBOOT")
                    }.toString(),
                )
            }
            // The existing qualified reboot/missing-record disposition is not a Runtime success or terminal record.
            com.helix.app.chat.JobObservationJournal(storage).record(
                com.helix.tools.framework.JobObservation(
                    observationBinding(binding),
                    "UNKNOWN",
                    terminal = false,
                    requiresReview = true,
                    settlementPending = false,
                    revision = "disposed",
                    observedAtMillis = System.currentTimeMillis(),
                ),
            )
        }
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
