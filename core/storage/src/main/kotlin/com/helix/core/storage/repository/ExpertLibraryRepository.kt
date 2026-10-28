package com.helix.core.storage.repository

import com.helix.core.storage.dao.ExpertDao
import com.helix.core.storage.entity.ExpertEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Saved profiles never change an already selected Session or admitted Turn. */
class ExpertLibraryRepository(
    private val dao: ExpertDao,
) {
    fun list(): List<ExpertProfile> = dao.list().map { it.profile() }

    fun find(id: String): ExpertProfile? = dao.find(id)?.profile()

    fun save(profile: ExpertProfile) {
        dao.save(ExpertEntity(profile.id, profile.displayName, profile.instruction))
    }

    private fun ExpertEntity.profile() = ExpertProfile(id, displayName, instruction)

    companion object {
        const val MAX_IMPORT_CHARS = 65_536

        /** Deliberately closed data format: no executable fields, authority or automatic activation. */
        fun parseImport(
            text: String,
            id: String,
        ): ExpertProfile {
            require(text.length <= MAX_IMPORT_CHARS) { "EXPERT_IMPORT_TOO_LARGE" }
            val obj = Json.parseToJsonElement(text) as? JsonObject ?: error("EXPERT_IMPORT_OBJECT_REQUIRED")
            require(obj.keys == setOf("name", "instruction")) { "EXPERT_IMPORT_FIELDS" }

            fun field(key: String): String {
                val value = obj[key] as? JsonPrimitive ?: error("EXPERT_IMPORT_STRING_REQUIRED")
                require(value.isString) { "EXPERT_IMPORT_STRING_REQUIRED" }
                return value.content.trim()
            }
            return ExpertProfile(id, field("name"), field("instruction"))
        }
    }
}
