package com.helix.core.storage.repository

import com.helix.core.model.AgentMode
import com.helix.core.storage.dao.SessionExpertDao
import com.helix.core.storage.entity.SessionExpertEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Session-level Expert is behavior guidance, never an authority grant. The repository owns the
 * single-row invariant and strict bounds; Turn admission snapshots the returned value separately.
 */
data class ExpertProfile(
    val id: String,
    val displayName: String,
    val instruction: String,
    val recommendedSkillIds: List<String> = emptyList(),
    val recommendedConnectorIds: List<String> = emptyList(),
    val recommendedMode: AgentMode? = null,
) {
    init {
        require(id.isNotBlank() && id.length <= MAX_ID_LENGTH && '\u0000' !in id)
        require(displayName.isNotBlank() && displayName.length <= MAX_NAME_LENGTH && '\u0000' !in displayName)
        require(instruction.isNotBlank() && instruction.length <= MAX_INSTRUCTION_LENGTH && '\u0000' !in instruction)
        require(recommendedSkillIds.size <= MAX_RECOMMENDATIONS)
        require(recommendedConnectorIds.size <= MAX_RECOMMENDATIONS)
        require(
            (recommendedSkillIds + recommendedConnectorIds).all {
                it.isNotBlank() && it.length <= 512 && '\u0000' !in it
            },
        )
    }

    companion object {
        const val MAX_ID_LENGTH = 128
        const val MAX_NAME_LENGTH = 80
        const val MAX_INSTRUCTION_LENGTH = 8_192
        const val MAX_RECOMMENDATIONS = 32
    }
}

class SessionExpertRepository(
    private val dao: SessionExpertDao,
) {
    fun forSession(sessionId: String): ExpertProfile? =
        dao.bySession(sessionId)?.takeIf { it.profileId.isNotBlank() }?.toProfile()

    fun bindingIdentity(sessionId: String): String? =
        dao
            .bySession(sessionId)
            ?.takeIf { it.profileId.isNotBlank() }
            ?.let { "${it.profileId}:${it.revision}" }

    fun setForSession(
        sessionId: String,
        profile: ExpertProfile,
        nowEpochMillis: Long,
    ): Long {
        require(sessionId.isNotBlank())
        val existing = dao.bySession(sessionId)
        check((existing?.revision ?: 0L) < Long.MAX_VALUE) { "EXPERT_REVISION_EXHAUSTED" }
        val revision = (existing?.revision ?: 0L) + 1L
        dao.insert(
            SessionExpertEntity(
                sessionId = sessionId,
                profileId = profile.id,
                displayName = profile.displayName.trim(),
                instruction = profile.instruction.trim(),
                recommendedSkillIdsJson = encodeIds(profile.recommendedSkillIds),
                recommendedConnectorIdsJson = encodeIds(profile.recommendedConnectorIds),
                recommendedMode = profile.recommendedMode?.name,
                revision = revision,
                createdAtEpoch = existing?.createdAtEpoch ?: nowEpochMillis,
                updatedAtEpoch = nowEpochMillis,
            ),
        )
        return revision
    }

    /** Keep the generation when returning to the default assistant; old grants must never revive. */
    fun clearForSession(
        sessionId: String,
        nowEpochMillis: Long,
    ): Boolean {
        val existing = dao.bySession(sessionId)?.takeIf { it.profileId.isNotBlank() } ?: return false
        check(existing.revision < Long.MAX_VALUE) { "EXPERT_REVISION_EXHAUSTED" }
        dao.insert(
            existing.copy(
                profileId = "",
                displayName = "",
                instruction = "",
                recommendedSkillIdsJson = "[]",
                recommendedConnectorIdsJson = "[]",
                recommendedMode = null,
                revision = existing.revision + 1,
                updatedAtEpoch = nowEpochMillis,
            ),
        )
        return true
    }

    private fun SessionExpertEntity.toProfile(): ExpertProfile =
        ExpertProfile(
            id = profileId,
            displayName = displayName,
            instruction = instruction,
            recommendedSkillIds = decodeIds(recommendedSkillIdsJson),
            recommendedConnectorIds = decodeIds(recommendedConnectorIdsJson),
            recommendedMode = recommendedMode?.let(AgentMode::valueOf),
        )

    private fun encodeIds(ids: List<String>): String = JsonArray(ids.distinct().map(::JsonPrimitive)).toString()

    private fun decodeIds(value: String): List<String> =
        (Json.parseToJsonElement(value) as JsonArray).map { it.jsonPrimitive.content }
}
