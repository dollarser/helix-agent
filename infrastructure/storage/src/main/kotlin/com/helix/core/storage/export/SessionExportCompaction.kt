package com.helix.core.storage.export

import com.helix.core.storage.content.ContentRef
import com.helix.core.storage.content.ContentStore
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException

internal class SessionExportCompaction(
    private val store: ContentStore,
    private val projection: SessionExportProjection,
) {
    fun describe(message: JsonObject): JsonObject {
        val ref = message["contentRef"]?.jsonPrimitive?.contentOrNull?.let(ContentRef::parse)
        val checkpoint = ref?.let(::read)
        return buildJsonObject {
            put("derived", true)
            put("derivationVersion", 1)
            put("messageId", message.getValue("id"))
            put("contentId", ref?.let { "content:${it.sha256}" })
            put("availability", if (checkpoint == null) "unavailable" else "persisted_checkpoint")
            put("coveredThrough", checkpoint?.get("coveredThrough") ?: JsonNull)
            put("preservedMessageIds", checkpoint?.get("preservedMessageIds") ?: JsonNull)
            put("sourceCallId", checkpoint?.get("sourceCallId") ?: JsonNull)
            put("estimatedInputTokens", checkpoint?.get("estimatedInputTokens") ?: JsonNull)
            val summary = checkpoint?.get("summary")?.jsonPrimitive?.contentOrNull
            put(
                "summary",
                summary?.let { projection.text(it) } ?: SessionExportProjection.unknown("checkpoint_unavailable"),
            )
        }
    }

    private fun read(ref: ContentRef): JsonObject? =
        if (ref.size > SessionExportFormat.LINE_BYTES) {
            null
        } else {
            try {
                SessionExportJson.objectOrNull(store.readBounded(ref, SessionExportFormat.LINE_BYTES))
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
}
