package com.helix.core.storage.repository

import com.helix.core.storage.dao.ExecutionDao
import com.helix.core.storage.entity.ExecutionEntity

class ExecutionRepository(
    private val dao: ExecutionDao,
) {
    fun register(
        id: String,
        toolCallId: String,
        runtime: String,
        limitsJson: String,
    ): ExecutionEntity {
        require(runtime.isNotBlank()) { "runtime must not be blank" }
        require(limitsJson.isNotBlank()) { "limitsJson must not be blank" }
        val entity = ExecutionEntity(id, toolCallId, runtime, limitsJson, null, null)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): ExecutionEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("execution not found: $id")
    }

    fun byToolCall(toolCallId: String): ExecutionEntity? = dao.byToolCall(toolCallId)

    fun updateOutcome(
        execution: ExecutionEntity,
        exitCode: Int?,
        signal: String?,
    ) {
        require(exitCode != null || signal != null) { "an outcome needs an exit code or a signal" }
        dao.updateOutcome(execution.id, exitCode, signal)
    }
}
