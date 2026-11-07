package com.helix.core.storage.repository

import com.helix.core.storage.dao.CapabilityGrantDao
import com.helix.core.storage.entity.CapabilityGrantEntity

class CapabilityGrantRepository(
    private val dao: CapabilityGrantDao,
) {
    fun record(
        type: String,
        systemState: String,
        userScopeRef: String,
        checkedAt: Long,
    ): CapabilityGrantEntity {
        require(type.isNotBlank()) { "type must not be blank" }
        require(systemState.isNotBlank()) { "systemState must not be blank" }
        require(userScopeRef.isNotBlank()) { "userScopeRef must not be blank" }
        require(checkedAt >= 0) { "checkedAt must be >= 0" }
        val rowId = dao.insert(CapabilityGrantEntity(0, type, systemState, userScopeRef, checkedAt))
        return resolve(rowId)
    }

    fun resolve(rowId: Long): CapabilityGrantEntity =
        dao.byRowId(rowId) ?: throw IllegalArgumentException("capability grant not found: $rowId")

    fun listByType(type: String): List<CapabilityGrantEntity> = dao.listByType(type)
}
