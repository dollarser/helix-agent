package com.helix.app.connector

import com.helix.core.storage.HelixStorage
import com.helix.core.storage.entity.ConnectorCatalogStateEntity
import com.helix.core.storage.entity.ConnectorInstallationEntity
import com.helix.core.storage.entity.ConnectorSkillOwnershipEntity
import com.helix.core.storage.entity.SessionConnectorEntity
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillSource
import java.nio.file.Files
import java.nio.file.Path

/** Durable installation/source facts. User choice and send admission share this local gate. */
@Suppress("TooManyFunctions") // one application facade for the installation/source facts
class ConnectorCatalog(
    private val storage: HelixStorage,
    legacyRoot: Path,
) {
    val mutationLock get() = storage.connectorMutationLock
    private val dao = storage.connectors

    init {
        storage.withTransaction {
            if (dao.migrated() == 0) {
                preserveLegacySkills(legacyRoot.parent.resolve("skills/snapshots"))
                if (Files.isDirectory(legacyRoot)) {
                    Files.list(legacyRoot).use { paths ->
                        paths.filter { it.fileName.toString().endsWith(".json") }.sorted().forEach { path ->
                            val legacy = decode(Files.readAllBytes(path).toString(Charsets.UTF_8))
                            val identified = legacyConnectorIdentity(legacy)
                            val record = if (list().any { it.identity == identified.identity }) legacy else identified
                            dao.insert(entity(record))
                            record.endpoints.forEach { claimEndpoint(it.id) }
                            // Old installs cannot prove absence of independent ownership.
                            record.skills.forEach { claim(it, independent = true) }
                            storage.sessions.list().forEach { session ->
                                if (record.sessionScoped) dao.select(SessionConnectorEntity(session.id, record.id))
                            }
                        }
                    }
                }
                dao.markMigrated(ConnectorCatalogStateEntity("legacy-imported"))
            }
        }
    }

    private fun preserveLegacySkills(root: Path) {
        if (!Files.isDirectory(root)) return
        Files.walk(root, 2).use { paths ->
            paths
                .filter { path ->
                    Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) &&
                        path.parent?.parent == root && path.fileName.toString().matches(Regex("[a-f0-9]{64}"))
                }.forEach { hashDirectory ->
                    claim(
                        SkillKey(
                            SkillSource.USER_IMPORTED,
                            hashDirectory.parent.fileName.toString(),
                            hashDirectory.fileName.toString(),
                        ),
                        independent = true,
                    )
                }
        }
    }

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
    fun list(): List<InstalledConnector> = dao.installations().map { decode(it.manifest) }

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
            list().any { key in it.skills && (!it.sessionScoped || selected == null || it.id in selected) }
    }

    @Synchronized
    fun endpointAvailable(
        serverId: String,
        sessionId: String?,
    ): Boolean {
        val owner = list().firstOrNull { record -> record.endpoints.any { it.id == serverId } }
        // Connector ids remain reserved after removal; stale schemas cannot regain availability.
        if (owner == null) return serverId !in dao.endpointIds()
        return sessionId == null || owner.id in dao.selected(sessionId)
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

    @Synchronized
    fun publish(
        record: InstalledConnector,
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
    fun remove(record: InstalledConnector) {
        check(dao.delete(record.id, record.revision) == 1) { "CONNECTOR_VERSION_CHANGED" }
    }

    private fun entity(record: InstalledConnector) =
        ConnectorInstallationEntity(record.id, record.identity, record.revision, encode(record), false)
}
