package com.helix.core.storage.repository

import com.helix.core.storage.dao.ProviderConfigDao
import com.helix.core.storage.entity.ProviderConfigEntity

/**
 * Provider configuration repository. The schema stores [ProviderConfigSpec.secretAlias]
 * only — there is no plaintext key or token column (architecture/overview §9.1 and
 * security/testing-and-release: API key/token 只保存
 * alias). [save] rejects duplicates; [overwrite] is the explicit replace; [delete] removes
 * the row (referencing sessions keep their rows with a nulled providerId FK).
 */
class ProviderConfigRepository(
    private val dao: ProviderConfigDao,
) {
    /** Inserts a new configuration; throws when the id already exists. */
    fun save(spec: ProviderConfigSpec): ProviderConfigEntity {
        val entity = spec.toEntity()
        dao.insert(entity)
        return entity
    }

    /**
     * Explicit overwrite of an existing (or new) configuration for the same id. The
     * existing case is an IN-PLACE UPDATE (row identity preserved): the DELETE+INSERT
     * of a REPLACE would fire `sessions.providerId`'s `ON DELETE SET NULL` and unbind
     * every session of the provider being edited (M3 closeout review bug).
     */
    fun overwrite(spec: ProviderConfigSpec): ProviderConfigEntity {
        val entity = spec.toEntity()
        if (
            dao.update(
                id = entity.id,
                displayName = entity.displayName,
                protocol = entity.protocol,
                endpoint = entity.endpoint,
                model = entity.model,
                headersJson = entity.headersJson,
                secretAlias = entity.secretAlias,
                capabilitySnapshot = entity.capabilitySnapshot,
            ) == 0
        ) {
            dao.insert(entity)
        }
        return entity
    }

    /** Deletes the configuration row; throws when the id does not exist. */
    fun delete(id: String) {
        require(dao.delete(id) == 1) { "provider config not found: $id" }
    }

    fun resolve(id: String): ProviderConfigEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("provider config not found: $id")
    }

    fun list(): List<ProviderConfigEntity> = dao.list()
}
