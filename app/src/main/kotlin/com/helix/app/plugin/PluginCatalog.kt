package com.helix.app.plugin

import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.ConnectorInstallationEntity
import com.helix.core.storage.entity.ConnectorSkillOwnershipEntity
import com.helix.core.storage.entity.SessionConnectorEntity
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillSource

/** Durable installation/source facts. User choice and send admission share this local gate. */
@Suppress("TooManyFunctions") // one application facade for the installation/source facts
class PluginCatalog(
    private val storage: HelixStorage,
    private val nativeSelection: (String, String, Boolean) -> Unit = { _, _, _ -> },
) {
    val mutationLock get() = storage.connectorMutationLock
    private val dao = storage.connectors

    fun claimEndpoint(id: String) {
        dao.claimEndpoint(
            com.helix.core.storage.entity
                .ConnectorEndpointEntity(id),
        )
    }

    fun releaseIndependent(key: SkillKey) {
        claim(key, independent = false)
        dao.setIndependent(key.source.name, key.name, key.snapshotHash, false)
    }

    fun unusedSkills(): List<SkillKey> {
        val active = list().flatMap { it.skills }.toSet()
        return dao
            .ownerships()
            .filter { !it.independent }
            .map {
                SkillKey(SkillSource.valueOf(it.source), it.name, it.hash)
            }.filter { it !in active }
    }

    fun retiredEndpoints(): List<String> {
        val active = list().flatMap { it.endpoints }.map { it.id }.toSet()
        return dao.endpointIds().filter { it !in active }
    }

    @Synchronized
    fun list(): List<InstalledPlugin> = dao.installations().map { decode(it.manifest) }

    fun independentlyInstalled(key: SkillKey): Boolean =
        dao
            .ownerships()
            .firstOrNull {
                it.source == key.source.name && it.name == key.name && it.hash == key.snapshotHash
            }?.independent ?: true

    @Synchronized
    fun claim(
        key: SkillKey,
        independent: Boolean,
    ) {
        dao.claim(ConnectorSkillOwnershipEntity(key.source.name, key.name, key.snapshotHash, independent))
        if (independent) dao.setIndependent(key.source.name, key.name, key.snapshotHash, true)
    }

    @Synchronized
    fun skillAvailable(
        key: SkillKey,
        sessionId: String?,
    ): Boolean {
        if (key.source != SkillSource.USER_IMPORTED) return true
        val ownership =
            dao.ownerships().firstOrNull {
                it.source == key.source.name && it.name == key.name && it.hash == key.snapshotHash
            }
        val selected = sessionId?.let(dao::selected)
        return ownership == null || ownership.independent ||
            list().any {
                it.enabled && key in it.skills && (!it.sessionScoped || selected == null || it.id in selected)
            }
    }

    @Synchronized
    fun endpointAvailable(
        serverId: String,
        sessionId: String?,
    ): Boolean {
        val owner = list().firstOrNull { record -> record.endpoints.any { it.id == serverId } }
        // Connector ids remain reserved after removal; stale schemas cannot regain availability.
        if (owner == null) return serverId !in dao.endpointIds()
        return owner.enabled && (sessionId == null || owner.id in dao.selected(sessionId))
    }

    @Synchronized
    fun sourceAvailable(
        sourceRef: String,
        sessionId: String?,
    ): Boolean =
        if (sourceRef.startsWith(
                "mcp:",
            )
        ) {
            endpointAvailable(
                sourceRef.removePrefix("mcp:").substringBeforeLast(':').substringBeforeLast(':'),
                sessionId,
            )
        } else if (sourceRef.startsWith("plugin:")) {
            val parts = sourceRef.split(':', limit = 4)
            val owner = list().singleOrNull { it.native?.pluginId == parts.getOrNull(1) }
            owner != null && owner.enabled && owner.versionLabel == parts.getOrNull(2) &&
                owner.native?.runtimeId == parts.getOrNull(3) &&
                (sessionId == null || owner.id in dao.selected(sessionId))
        } else {
            true
        }

    @Synchronized
    fun selected(sessionId: String): Set<String> = dao.selected(sessionId).toSet()

    @Synchronized
    fun select(
        sessionId: String,
        connectorId: String,
        enabled: Boolean,
    ) {
        storage.sessions.resolve(sessionId)
        require(list().any { it.id == connectorId } || !enabled) { "CONNECTOR_UNAVAILABLE" }
        list().singleOrNull { it.id == connectorId }?.native?.let {
            nativeSelection(sessionId, it.pluginId, enabled)
        }
        if (enabled) {
            dao.select(
                SessionConnectorEntity(sessionId, connectorId),
            )
        } else {
            dao.deselect(sessionId, connectorId)
        }
    }

    @Synchronized
    fun setDefault(
        connectorId: String,
        enabled: Boolean,
    ) {
        check(dao.setDefault(connectorId, enabled) == 1) { "CONNECTOR_UNAVAILABLE" }
    }

    fun defaultSelected(connectorId: String): Boolean =
        dao.installations().single { it.id == connectorId }.defaultSelected

    /** Installing a native plugin never selects it in a conversation or grants its scope. */
    @Synchronized
    fun publishNative(
        record: InstalledPlugin,
        expectedRevision: Long?,
    ) {
        require(record.source == "BUNDLED_PLUGIN" && record.native != null)
        storage.withTransaction {
            publish(record, expectedRevision)
            if (expectedRevision == null) {
                check(dao.setDefault(record.id, false) == 1)
            }
        }
    }

    @Synchronized
    fun publish(
        record: InstalledPlugin,
        expectedRevision: Long?,
    ) {
        storage.withTransaction {
            if (expectedRevision == null) {
                dao.insert(entity(record))
            } else {
                check(dao.replace(record.id, expectedRevision, record.revision, encode(record)) == 1) {
                    "CONNECTOR_VERSION_CHANGED"
                }
            }
        }
    }

    @Synchronized
    fun remove(record: InstalledPlugin) {
        check(dao.delete(record.id, record.revision) == 1) { "CONNECTOR_VERSION_CHANGED" }
    }

    private fun entity(record: InstalledPlugin) =
        ConnectorInstallationEntity(record.id, record.identity, record.revision, encode(record), false)
}
