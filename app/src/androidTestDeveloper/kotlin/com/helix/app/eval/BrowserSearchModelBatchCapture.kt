package com.helix.app.eval

import com.helix.app.agent.ChatHistoryBuilder
import com.helix.core.storage.HelixStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Export the original persisted wire/local identity join, including unbackfilled review calls. */
internal fun captureBrowserModelBatches(
    storage: HelixStorage,
    directory: File,
    turnIds: Set<String>,
) {
    require(turnIds.isNotEmpty())
    val session = storage.turns.resolve(turnIds.first()).sessionId
    require(turnIds.all { storage.turns.resolve(it).sessionId == session })
    val batches =
        storage.messages
            .listBySession(session)
            .filter { it.turnId in turnIds && it.role == "ASSISTANT" && it.kind == ChatHistoryBuilder.KIND_TOOL_CALLS }
            .map {
                JSONObject()
                    .put("turnId", it.turnId)
                    .put("messageId", it.id)
                    .put("calls", JSONArray(requireNotNull(storage.messages.readContent(it))))
            }
    File(directory, "persisted-batches.json").writeText(JSONArray(batches).toString(2))
}
