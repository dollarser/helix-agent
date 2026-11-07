package com.helix.core.storage.repository

import com.helix.core.storage.dao.ExecutionTargetDao
import com.helix.core.storage.entity.ExecutionTargetEntity

class ExecutionTargetRepository(
    private val dao: ExecutionTargetDao,
) {
    /** [descriptor] must be the canonical `ExecutionTargetDescriptor` storage string (ADR-0001). */
    fun register(
        id: String,
        type: String,
        descriptor: String,
        capabilitySnapshot: String,
    ): ExecutionTargetEntity {
        require(type.isNotBlank()) { "type must not be blank" }
        require(descriptor.isNotBlank()) { "descriptor must not be blank" }
        val entity = ExecutionTargetEntity(id, type, descriptor, capabilitySnapshot)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): ExecutionTargetEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("execution target not found: $id")
    }

    fun list(): List<ExecutionTargetEntity> = dao.list()
}
