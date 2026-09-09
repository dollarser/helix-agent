package com.helix.core.storage.repository

import com.helix.core.model.ToolCallState
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.dao.ToolCallDao
import com.helix.core.storage.entity.ToolCallEntity

class ToolCallRepository(
    private val dao: ToolCallDao,
) {
    /**
     * Registers a tool call. `argsJson` must already be canonical (doc 9.2); [argsHash] is
     * computed here so the hash and the stored body cannot drift.
     */
    fun append(
        id: String,
        turnId: String,
        callId: String,
        name: String,
        version: String,
        argsJson: String,
        state: String,
    ): ToolCallEntity {
        require(callId.isNotBlank()) { "callId must not be blank" }
        require(name.isNotBlank()) { "tool name must not be blank" }
        require(version.isNotBlank()) { "tool version must not be blank" }
        require(state.isNotBlank()) { "state must not be blank" }
        val entity =
            ToolCallEntity(
                id,
                turnId,
                callId,
                name,
                version,
                argsJson,
                FileContentStore.sha256Hex(argsJson.toByteArray(Charsets.UTF_8)),
                state,
            )
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): ToolCallEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("tool call not found: $id")
    }

    fun listByTurn(turnId: String): List<ToolCallEntity> = dao.listByTurn(turnId)

    fun byTurnAndCallId(
        turnId: String,
        callId: String,
    ): ToolCallEntity? = dao.byTurnAndCallId(turnId, callId)

    /**
     * Updates a tool call to [state]. The [ToolCallState] is validated before it hits the column
     * (HXA-015 recovery parks in-flight calls in [ToolCallState.INTERRUPTED]; a parked state must
     * never silently degrade to an arbitrary string).
     */
    fun updateState(
        call: ToolCallEntity,
        state: ToolCallState,
    ) {
        dao.updateState(call.id, state.name)
    }
}
