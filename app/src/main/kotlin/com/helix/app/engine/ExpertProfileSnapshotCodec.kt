package com.helix.app.engine

import com.helix.core.model.AgentMode
import com.helix.core.storage.repository.ExpertProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Deterministic codec for the Expert bytes frozen onto one admitted Turn. */
internal object ExpertProfileSnapshotCodec {
    fun encode(profile: ExpertProfile): String =
        buildJsonObject {
            put("version", 1)
            put("id", profile.id)
            put("displayName", profile.displayName)
            put("instruction", profile.instruction)
            put(
                "recommendedSkillIds",
                buildJsonArray { profile.recommendedSkillIds.forEach { add(JsonPrimitive(it)) } },
            )
            put(
                "recommendedConnectorIds",
                buildJsonArray { profile.recommendedConnectorIds.forEach { add(JsonPrimitive(it)) } },
            )
            profile.recommendedMode?.let { put("recommendedMode", it.name) }
        }.toString()

    fun decode(value: String): ExpertProfile {
        val obj = Json.parseToJsonElement(value).jsonObject
        require(obj.getValue("version").jsonPrimitive.content == "1") { "unsupported Expert snapshot version" }
        return ExpertProfile(
            id = obj.getValue("id").jsonPrimitive.content,
            displayName = obj.getValue("displayName").jsonPrimitive.content,
            instruction = obj.getValue("instruction").jsonPrimitive.content,
            recommendedSkillIds = obj.getValue("recommendedSkillIds").jsonArray.map { it.jsonPrimitive.content },
            recommendedConnectorIds =
                obj.getValue("recommendedConnectorIds").jsonArray.map { it.jsonPrimitive.content },
            recommendedMode =
                obj["recommendedMode"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.let(AgentMode::valueOf),
        )
    }
}
