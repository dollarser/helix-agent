package com.helix.core.storage.repository

import com.helix.core.storage.dao.ModelCallDao
import com.helix.core.storage.entity.ModelCallEntity

class ModelCallRepository(
    private val dao: ModelCallDao,
) {
    fun append(
        id: String,
        turnId: String,
        providerSnapshot: String,
        state: String,
    ): ModelCallEntity {
        require(providerSnapshot.isNotBlank()) { "providerSnapshot must not be blank" }
        require(state.isNotBlank()) { "state must not be blank" }
        val entity = ModelCallEntity(id, turnId, providerSnapshot, state, null, null)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): ModelCallEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("model call not found: $id")
    }

    fun listByTurn(turnId: String): List<ModelCallEntity> = dao.listByTurn(turnId)

    /** Reconcile abandoned calls, including rows left by older recovery versions; keep known metadata. */
    fun interruptForInterruptedTurns(): Int = dao.interruptForInterruptedTurns()

    fun update(
        call: ModelCallEntity,
        state: String,
        usage: String?,
        requestId: String?,
    ) {
        require(state.isNotBlank()) { "state must not be blank" }
        dao.update(call.id, state, usage, requestId)
    }
}
