package com.helix.core.storage.repository

import com.helix.core.storage.dao.ExpertDao
import com.helix.core.storage.entity.ExpertEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Library card metadata is separate from the runtime behavior/capability snapshot. */
data class ExpertDefinition(
    val profile: ExpertProfile,
    val description: String = "",
) {
    init {
        require(description.length <= 280 && '\u0000' !in description)
        require(profile.recommendedMode == null) { "EXPERT_INHERITS_SESSION_MODE" }
    }
}

/** Saved profiles never change an already selected Session or admitted Turn. */
class ExpertLibraryRepository(
    private val dao: ExpertDao,
) {
    fun list(): List<ExpertDefinition> = dao.list().map { it.definition() }

    fun find(id: String): ExpertDefinition? = dao.find(id)?.definition()

    fun remove(id: String): Boolean = dao.delete(id) == 1

    fun save(definition: ExpertDefinition) {
        val profile =
            definition.profile.copy(
                displayName = definition.profile.displayName.trim(),
                instruction = definition.profile.instruction.trim(),
            )
        dao.save(ExpertEntity(profile.id, profile.displayName, export(definition.copy(profile = profile))))
    }

    private fun ExpertEntity.definition() =
        parseImport(instruction, id).also {
            require(it.profile.displayName == displayName) { "EXPERT_CONFIGURATION_CHANGED" }
        }

    companion object {
        const val MAX_IMPORT_CHARS = 65_536

        /** Deliberately closed data format: no executable fields, authority or automatic activation. */
        fun parseImport(
            text: String,
            id: String,
        ): ExpertDefinition {
            require(text.length <= MAX_IMPORT_CHARS) { "EXPERT_IMPORT_TOO_LARGE" }
            val obj = Json.parseToJsonElement(text) as? JsonObject ?: error("EXPERT_IMPORT_OBJECT_REQUIRED")
            require(
                obj.keys.containsAll(setOf("name", "instruction")) &&
                    obj.keys.all { it in setOf("name", "description", "instruction", "plugins", "skills") },
            ) { "EXPERT_IMPORT_FIELDS" }

            fun field(key: String): String {
                val value = obj[key] as? JsonPrimitive ?: error("EXPERT_IMPORT_STRING_REQUIRED")
                require(value.isString) { "EXPERT_IMPORT_STRING_REQUIRED" }
                return value.content.trim()
            }

            fun dependencies(key: String): List<String> {
                val value = obj[key] ?: return emptyList()
                require(value is JsonArray) { "EXPERT_DEPENDENCIES_REQUIRED" }
                return value
                    .map {
                        require(it is JsonPrimitive && it.isString) { "EXPERT_DEPENDENCY_STRING_REQUIRED" }
                        it.content
                    }.also { require(it.distinct().size == it.size) { "EXPERT_DUPLICATE_DEPENDENCY" } }
            }
            return ExpertDefinition(
                ExpertProfile(
                    id,
                    field("name"),
                    field("instruction"),
                    recommendedSkillIds = dependencies("skills"),
                    recommendedConnectorIds = dependencies("plugins"),
                ),
                if ("description" in obj) field("description") else "",
            )
        }

        fun export(definition: ExpertDefinition): String =
            buildJsonObject {
                val profile = definition.profile
                put("name", profile.displayName)
                put("description", definition.description)
                put("instruction", profile.instruction)
                put("plugins", JsonArray(profile.recommendedConnectorIds.map(::JsonPrimitive)))
                put("skills", JsonArray(profile.recommendedSkillIds.map(::JsonPrimitive)))
            }.toString()
    }
}
