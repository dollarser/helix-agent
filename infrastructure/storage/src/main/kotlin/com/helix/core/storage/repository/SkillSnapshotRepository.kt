package com.helix.core.storage.repository

import com.helix.core.storage.dao.SkillSnapshotDao
import com.helix.core.storage.entity.SkillSnapshotEntity

class SkillSnapshotRepository(
    private val dao: SkillSnapshotDao,
) {
    fun record(
        runId: String,
        skillId: String,
        contentHash: String,
        catalogEntry: String,
    ): SkillSnapshotEntity {
        require(runId.isNotBlank()) { "runId must not be blank" }
        require(skillId.isNotBlank()) { "skillId must not be blank" }
        require(contentHash.length == 64) { "contentHash must be a sha256 hex string" }
        require(catalogEntry.isNotBlank()) { "catalogEntry must not be blank" }
        val entity = SkillSnapshotEntity(runId, skillId, contentHash, catalogEntry)
        dao.insert(entity)
        return entity
    }

    fun listByRun(runId: String): List<SkillSnapshotEntity> = dao.listByRun(runId)
}
