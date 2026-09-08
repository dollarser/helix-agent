package com.helix.app.provider

import com.helix.core.model.ModelRequest
import com.helix.core.storage.HelixStorage
import com.helix.runtime.cli.client.CliModelProvider
import com.helix.runtime.cli.client.CliModelRequestCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest

/** Write-ahead subscription Job identity; request bodies and credentials are never persisted here. */
internal class SubscriptionJobBindingStore(
    private val storage: HelixStorage,
) {
    fun record(
        ownership: LocalModelCallContext,
        jobId: String,
        request: ModelRequest,
        platform: CliModelProvider,
    ) {
        val turn = storage.turns.resolve(ownership.turnId)
        val payload = CliModelRequestCodec.encode(request, platform)
        val hash = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        storage.auditEvents.append(
            id = eventId(ownership.modelCallId),
            correlationId = turn.sessionId,
            type = "cli.job_prepared",
            actor = "platform",
            redactedPayload =
                buildJsonObject {
                    put("version", 1)
                    put("turnId", turn.id)
                    put("modelCallId", ownership.modelCallId)
                    put("jobId", jobId)
                    put("platform", platform.name)
                    put("requestSha256", hash)
                }.toString(),
            timestamp = System.currentTimeMillis(),
        )
    }

    fun resolve(modelCallId: String): JsonObject {
        val event = storage.auditEvents.resolve(eventId(modelCallId))
        check(event.type == "cli.job_prepared")
        val payload = Json.parseToJsonElement(event.redactedPayload) as JsonObject
        check(payload.getValue("version").jsonPrimitive.content == "1")
        check(payload.getValue("modelCallId").jsonPrimitive.content == modelCallId)
        return payload
    }

    private fun eventId(modelCallId: String): String = "cli-job-$modelCallId"
}
