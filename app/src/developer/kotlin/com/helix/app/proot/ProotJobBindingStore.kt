package com.helix.app.proot

import com.helix.core.storage.HelixStorage
import com.helix.runtime.proot.ipc.ProotJobSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Write-ahead identity only; never authorization or proof that submission succeeded. */
internal class ProotJobBindingStore(
    private val storage: HelixStorage,
) {
    fun record(
        call: LinuxRunTool.ParsedLinuxCall,
        spec: ProotJobSpec,
    ) {
        val turn = storage.turns.resolve(requireNotNull(call.turnId))
        val stored = requireNotNull(storage.toolCalls.byTurnAndCallId(turn.id, call.toolCallId))
        check(stored.state == "RUNNING")
        val payload =
            buildJsonObject {
                put("version", 1)
                put("toolCallId", stored.callId)
                put("turnId", turn.id)
                put("jobId", spec.jobId)
                put("executionId", spec.executionId)
                put("inputManifestSha256", spec.inputManifestSha256)
            }
        storage.auditEvents.append(
            id = eventId(call.toolCallId),
            correlationId = turn.sessionId,
            type = "proot.job_prepared",
            actor = "platform",
            redactedPayload = payload.toString(),
            timestamp = System.currentTimeMillis(),
        )
    }

    fun resolve(toolCallId: String): JsonObject {
        val event = storage.auditEvents.resolve(eventId(toolCallId))
        check(event.type == "proot.job_prepared")
        val payload = Json.parseToJsonElement(event.redactedPayload) as JsonObject
        check(payload.getValue("version").jsonPrimitive.content == "1")
        check(payload.getValue("toolCallId").jsonPrimitive.content == toolCallId)
        return payload
    }

    private fun eventId(toolCallId: String): String = "proot-job-$toolCallId"
}
