package com.helix.core.storage.repository

import com.helix.core.storage.dao.RuntimeInstallDao
import com.helix.core.storage.entity.RuntimeInstallEntity

class RuntimeInstallRepository(
    private val dao: RuntimeInstallDao,
) {
    fun register(
        id: String,
        type: String,
        version: String,
        state: String,
        manifestHash: String,
        installedAt: Long,
    ): RuntimeInstallEntity {
        require(type.isNotBlank()) { "type must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
        require(state.isNotBlank()) { "state must not be blank" }
        require(manifestHash.length == 64) { "manifestHash must be a sha256 hex string" }
        require(installedAt >= 0) { "installedAt must be >= 0" }
        val entity = RuntimeInstallEntity(id, type, version, state, manifestHash, installedAt)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): RuntimeInstallEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("runtime install not found: $id")
    }

    fun list(): List<RuntimeInstallEntity> = dao.list()

    fun updateState(
        install: RuntimeInstallEntity,
        state: String,
    ) {
        require(state.isNotBlank()) { "state must not be blank" }
        dao.updateState(install.id, state)
    }
}
