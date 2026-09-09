package com.helix.core.storage.repository

import com.helix.core.model.McpServerId
import com.helix.core.storage.dao.McpCapabilityDao
import com.helix.core.storage.entity.McpCapabilityEntity

class McpCapabilityRepository(
    private val dao: McpCapabilityDao,
) {
    /**
     * Atomically replaces one server's handshake snapshot. Exact unchanged tools retain the
     * user's enabled bit; a protocol/hash change and every new entry start disabled.
     */
    fun replaceSnapshot(
        serverId: String,
        capabilities: List<McpCapabilitySpec>,
    ): List<McpCapabilityEntity> {
        McpServerId(serverId)
        require(capabilities.size <= MAX_MCP_CAPABILITIES) {
            "MCP capability snapshot exceeds $MAX_MCP_CAPABILITIES entries"
        }
        require(capabilities.map { it.kind to it.name }.toSet().size == capabilities.size) {
            "MCP capability snapshot contains duplicate kind/name entries"
        }
        val previous = dao.listByServer(serverId).associateBy { it.kind to it.name }
        val replacements =
            capabilities.map { spec ->
                val old = previous[spec.kind.storageValue to spec.name]
                val unchanged =
                    old != null &&
                        old.protocolVersion == spec.protocolVersion &&
                        old.schemaHash == spec.contentHash
                spec.toEntity(
                    serverId = serverId,
                    enabled = unchanged && old.enabled && spec.kind == McpCapabilityKind.TOOL,
                )
            }
        dao.replaceForServer(serverId, replacements)
        return dao.listByServer(serverId)
    }

    fun register(
        serverId: String,
        protocolVersion: String,
        kind: String,
        name: String,
        schemaHash: String,
        enabled: Boolean,
    ): McpCapabilityEntity {
        require(protocolVersion.isNotBlank()) { "protocolVersion must not be blank" }
        require(kind.isNotBlank()) { "kind must not be blank" }
        require(name.isNotBlank()) { "name must not be blank" }
        require(schemaHash.length == 64) { "schemaHash must be a sha256 hex string" }
        val rowId = dao.insert(McpCapabilityEntity(0, serverId, protocolVersion, kind, name, schemaHash, enabled))
        return resolve(serverId, rowId)
    }

    private fun resolve(
        serverId: String,
        rowId: Long,
    ): McpCapabilityEntity =
        dao.listByServer(serverId).firstOrNull { it.rowId == rowId }
            ?: throw IllegalArgumentException("mcp capability not found: $rowId")

    fun listByServer(serverId: String): List<McpCapabilityEntity> = dao.listByServer(serverId)

    fun setEnabled(
        rowId: Long,
        enabled: Boolean,
    ) {
        dao.setEnabled(rowId, enabled)
    }
}
