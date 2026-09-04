package com.helix.core.storage.repository

import com.helix.core.model.McpServerId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderHeaders
import com.helix.core.model.ProviderId
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import com.helix.core.model.SecretAlias
import com.helix.core.storage.dao.CapabilityGrantDao
import com.helix.core.storage.dao.ExecutionTargetDao
import com.helix.core.storage.dao.McpCapabilityDao
import com.helix.core.storage.dao.McpServerDao
import com.helix.core.storage.dao.ProviderConfigDao
import com.helix.core.storage.dao.RuntimeInstallDao
import com.helix.core.storage.dao.SkillDao
import com.helix.core.storage.dao.SkillSnapshotDao
import com.helix.core.storage.entity.CapabilityGrantEntity
import com.helix.core.storage.entity.ExecutionTargetEntity
import com.helix.core.storage.entity.McpCapabilityEntity
import com.helix.core.storage.entity.McpServerEntity
import com.helix.core.storage.entity.ProviderConfigEntity
import com.helix.core.storage.entity.RuntimeInstallEntity
import com.helix.core.storage.entity.SkillEntity
import com.helix.core.storage.entity.SkillSnapshotEntity

/**
 * Provider configuration input (doc 9.1 `provider_configs` row fields). [protocol] is the
 * closed [ProviderProtocol] enum; [endpoint] is a raw URL fully validated (and stored in
 * canonical normalized form); [headersJson] must pass the [ProviderHeaders] allowlist;
 * [secretAlias] must be a legal [SecretAlias] (the credential itself lives only in the
 * SecretStore — no plaintext column, architecture/overview §9.1 and
 * security/testing-and-release).
 */
data class ProviderConfigSpec(
    val id: String,
    val displayName: String,
    val protocol: ProviderProtocol,
    val endpoint: String,
    val model: String,
    val headersJson: String,
    val secretAlias: String,
    val capabilitySnapshot: String,
) {
    /** Validated, canonical row shared by save/overwrite; fails closed on any violation. */
    internal fun toEntity(): ProviderConfigEntity {
        ProviderId(id)
        require(displayName.isNotBlank() && displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "displayName must be 1..$MAX_DISPLAY_NAME_LENGTH non-blank chars"
        }
        val normalized = NormalizedEndpoint.parse(endpoint)
        require(model.isNotBlank() && model.length <= MAX_MODEL_LENGTH) {
            "model must be 1..$MAX_MODEL_LENGTH non-blank chars"
        }
        require(model.none { it <= ' ' || it == '\u007F' }) { "model contains control characters" }
        val headers = ProviderHeaders.parse(headersJson)
        SecretAlias(secretAlias)
        require(capabilitySnapshot.isNotBlank()) { "capabilitySnapshot must not be blank" }
        return ProviderConfigEntity(
            id,
            displayName,
            protocol.name,
            normalized.full,
            model,
            ProviderHeaders.toStorageString(headers),
            secretAlias,
            capabilitySnapshot,
        )
    }

    private companion object {
        const val MAX_DISPLAY_NAME_LENGTH = 128
        const val MAX_MODEL_LENGTH = 256
    }
}

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

    fun update(
        server: McpServerEntity,
        enabled: Boolean,
        trustState: String,
    ) {
        require(trustState.isNotBlank()) { "trustState must not be blank" }
        dao.update(server.id, enabled, trustState)
    }
}

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

data class McpHttpServerSpec(
    val id: String,
    val endpoint: String,
    val authAlias: String?,
) {
    internal fun toEntity(): McpServerEntity {
        McpServerId(id)
        val normalized = NormalizedEndpoint.parse(endpoint)
        val residence = normalized.residence()
        require(
            normalized.scheme == "https" ||
                residence == ProviderResidence.ON_DEVICE_LOOPBACK ||
                residence == ProviderResidence.USER_AUTHORIZED_LAN,
        ) {
            "cleartext MCP is limited to loopback or LAN endpoints"
        }
        authAlias?.let(::SecretAlias)
        return McpServerEntity(
            id = id,
            transport = MCP_STREAMABLE_HTTP,
            endpointRef = normalized.full,
            commandRef = null,
            authAlias = authAlias,
            enabled = false,
            trustState = MCP_UNTRUSTED,
        )
    }
}

data class McpCapabilitySpec(
    val protocolVersion: String,
    val kind: McpCapabilityKind,
    val name: String,
    val contentHash: String,
) {
    init {
        require(protocolVersion.isNotBlank() && protocolVersion.length <= 64) {
            "protocolVersion must be 1..64 non-blank characters"
        }
        require(name.isNotBlank() && name.length <= 4_096) { "name must be 1..4096 non-blank characters" }
        require(contentHash.length == 64 && contentHash.all { it in '0'..'9' || it in 'a'..'f' }) {
            "contentHash must be lowercase sha256 hex"
        }
    }

    internal fun toEntity(
        serverId: String,
        enabled: Boolean,
    ): McpCapabilityEntity =
        McpCapabilityEntity(
            rowId = 0,
            serverId = serverId,
            protocolVersion = protocolVersion,
            kind = kind.storageValue,
            name = name,
            schemaHash = contentHash,
            enabled = enabled,
        )
}

enum class McpCapabilityKind(
    internal val storageValue: String,
) {
    CAPABILITY("capability"),
    TOOL("tool"),
    RESOURCE("resource"),
    PROMPT("prompt"),
}

private const val MCP_STREAMABLE_HTTP = "streamable-http"
private const val MCP_UNTRUSTED = "UNTRUSTED"
private const val MAX_MCP_CAPABILITIES = 3_072

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
}

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
