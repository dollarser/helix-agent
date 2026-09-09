package com.helix.core.storage.repository

import com.helix.core.storage.dao.SkillDao
import com.helix.core.storage.entity.SkillEntity

class SkillRepository(
    private val dao: SkillDao,
) {
    fun register(
        id: String,
        name: String,
        source: String,
        version: String,
        rootRef: String,
        contentHash: String,
    ): SkillEntity {
        require(name.isNotBlank()) { "name must not be blank" }
        require(source.isNotBlank()) { "source must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
        require(rootRef.isNotBlank()) { "rootRef must not be blank" }
        require(contentHash.length == 64) { "contentHash must be a sha256 hex string" }
        val entity = SkillEntity(id, name, source, version, rootRef, contentHash, true)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): SkillEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("skill not found: $id")
    }

    fun list(): List<SkillEntity> = dao.list()

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        dao.setEnabled(id, enabled)
    }

    fun delete(id: String) {
        require(dao.delete(id) == 1) { "skill not found: $id" }
    }
}
