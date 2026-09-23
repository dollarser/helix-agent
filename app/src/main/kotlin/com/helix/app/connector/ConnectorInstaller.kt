package com.helix.app.connector

import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillImportService
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillRepository
import com.helix.extensions.skills.SkillSource
import com.helix.extensions.skills.connector.ConnectorPackage
import com.helix.extensions.skills.connector.ConnectorPackageReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** Preparation has no published references. Room publishes the complete manifest once. */
internal class ConnectorInstaller(
    private val cache: Path,
    private val snapshots: Path,
    private val importer: SkillImportService,
    private val skills: SkillRepository,
    private val catalog: ConnectorCatalog,
    private val boundary: (String) -> Unit,
    private val cleanupFailed: () -> Unit,
) {
    fun install(
        bundle: ConnectorPackage,
        identity: String,
        expectedRevision: Long?,
        cancelled: () -> Boolean,
        sessionScoped: Boolean,
    ): InstalledConnector {
        val previous = catalog.list().singleOrNull { it.identity == identity }
        if (previous?.hash == bundle.contentHash) return previous
        require(previous?.revision == expectedRevision) { "CONNECTOR_VERSION_CHANGED" }
        require(bundle.endpoints.isNotEmpty() || bundle.skills.isNotEmpty()) { "CONNECTOR_NO_PORTABLE_COMPONENT" }
        require(identity.isNotBlank() && identity.length <= 512)
        val id = previous?.id ?: "conn-" + UUID.randomUUID()
        val staging = Files.createTempDirectory(cache, "connector-skills-")
        try {
            val keys = prepareSkills(bundle, staging, previous, cancelled)
            val endpoints =
                bundle.endpoints.map { endpoint ->
                    val old =
                        previous?.endpoints?.singleOrNull {
                            it.endpoint.copy(name = endpoint.name) == endpoint
                        }
                    InstalledEndpoint(old?.id ?: "ce-" + UUID.randomUUID(), endpoint).also {
                        catalog.claimEndpoint(it.id)
                    }
                }
            val record =
                InstalledConnector(
                    id,
                    bundle.name,
                    bundle.source,
                    bundle.contentHash,
                    endpoints,
                    keys,
                    bundle.diagnostics,
                    identity,
                    (previous?.revision ?: 0) + 1,
                    sessionScoped,
                    bundle.versionLabel,
                    bundle.releaseNotes,
                )
            boundary("before-commit")
            check(!cancelled()) { "IMPORT_CANCELLED" }
            catalog.publish(record, expectedRevision)
            boundary("after-commit")
            return record
        } finally {
            runCatching {
                Files.walk(staging).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }.onFailure { cleanupFailed() }
        }
    }

    private fun prepareSkills(
        bundle: ConnectorPackage,
        staging: Path,
        previous: InstalledConnector?,
        cancelled: () -> Boolean,
    ): List<SkillKey> {
        val enabled = skills.list().filter { it.enabled && it.key in previous?.skills.orEmpty() }.map { it.key.name }
        val published = catalog.list().flatMap { it.skills }.toSet()
        val scopedImporter = importer.withStagingRoot(staging.resolve("imports"))
        return bundle.skills.mapIndexed { index, skill ->
            check(!cancelled()) { "IMPORT_CANCELLED" }
            val directory = staging.resolve(index.toString()).resolve(skill.directory)
            skill.files.forEach { (name, bytes) ->
                val target = directory.resolve(ConnectorPackageReader.safePath(name))
                Files.createDirectories(target.parent)
                Files.write(target, bytes)
            }
            val staged = scopedImporter.stageDirectory(directory)
            try {
                val key = SkillKey(SkillSource.USER_IMPORTED, staged.preview.name, staged.preview.snapshotHash)
                // This marker precedes placement, so a crash cannot turn a candidate into an independent skill.
                catalog.claim(key, independent = false)
                boundary("preparing")
                val existed = skills.hasSnapshot(key)
                val registered = skills.registerSnapshot(scopedImporter.commit(staged, snapshots), independent = false)
                if (!existed && key !in published && key.name in enabled) {
                    skills.setEnabled(registered, true, SkillEnablementScope.GLOBAL)
                }
                registered
            } finally {
                runCatching { scopedImporter.discard(staged) }.onFailure { cleanupFailed() }
            }
        }
    }
}
