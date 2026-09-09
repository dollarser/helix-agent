package com.helix.core.storage.repository

import com.helix.core.model.SecretAlias
import com.helix.core.storage.dao.McpServerDao
import com.helix.core.storage.entity.McpServerEntity

class McpServerRepository(
    private val dao: McpServerDao,
) {
    fun registerHttp(spec: McpHttpServerSpec): McpServerEntity {
        val entity = spec.toEntity()
        dao.insert(entity)
        return entity
    }

    /**
     * [authAlias] is an alias only; credentials never enter the schema (doc 9.1). Servers are
     * registered disabled by default (roadmap HXA-071: disabled-by-default MCP config) and can
     * be enabled through [update] after the user approves the server.
     */
    fun register(
        id: String,
        transport: String,
        endpointRef: String?,
        commandRef: String?,
        authAlias: String?,
        trustState: String,
    ): McpServerEntity {
        require(transport.isNotBlank()) { "transport must not be blank" }
        require(trustState.isNotBlank()) { "trustState must not be blank" }
        require(endpointRef != null || commandRef != null) {
            "mcp server needs an endpoint reference or a command reference"
        }
        val entity = McpServerEntity(id, transport, endpointRef, commandRef, authAlias, false, trustState)
        dao.insert(entity)
        return entity
    }

    fun resolve(id: String): McpServerEntity {
        val entity = dao.byId(id)

        return entity ?: throw IllegalArgumentException("mcp server not found: $id")
    }

    fun list(): List<McpServerEntity> = dao.list()

    /** User credential replacement invalidates enablement; the value is an alias only. */
    fun replaceAuthAlias(
        id: String,
        alias: String?,
    ) {
        resolve(id)
        alias?.let {
            com.helix.core.model
                .SecretAlias(it)
        }
        dao.replaceAuthAlias(id, alias)
    }

    fun update(
        server: McpServerEntity,
        enabled: Boolean,
        trustState: String,
    ) {
        require(trustState.isNotBlank()) { "trustState must not be blank" }
        dao.update(server.id, enabled, trustState)
    }

    fun delete(id: String) {
        require(dao.delete(id) == 1) { "mcp server not found: $id" }
    }
}
