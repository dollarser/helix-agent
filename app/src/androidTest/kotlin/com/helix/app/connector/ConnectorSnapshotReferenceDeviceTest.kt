package com.helix.app.connector

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.plugin.PluginService
import com.helix.extensions.plugin.PluginPackageReader
import com.helix.extensions.skills.SkillEnablementScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ConnectorSnapshotReferenceDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val c get() = app.appContainer

    @Test
    fun cancelledCandidateNeverBecomesIndependentOrReplacesPublishedOwner() {
        val identity = "snapshot-reference:${UUID.randomUUID()}"
        val name = "reference-${UUID.randomUUID().toString().take(8)}"
        val original = c.pluginService.install(bundle(name, "old"), identity)
        val originalKey = original.skills.single()
        var cancelled = false
        val service =
            PluginService(
                app,
                c.storage,
                c.mcpService,
                c.skillImportService,
                c.skillRepository,
                catalog = c.pluginService.catalog,
                installBoundary = { point -> if (point == "before-commit") cancelled = true },
            )
        try {
            assertThrows(IllegalStateException::class.java) {
                service.install(bundle(name, "candidate"), identity, original.revision, cancelled = { cancelled })
            }
            assertEquals(original, service.list().single { it.identity == identity })
            service.cleanupRetired()
            val remaining = c.skillRepository.list().filter { it.key.name == name }
            assertEquals(listOf(originalKey), remaining.map { it.key })
            assertTrue(c.skillRepository.listSelectable().none { it.key.name == name })
        } finally {
            service.list().filter { it.identity == identity }.forEach(service::remove)
        }
    }

    @Test
    fun deferredPluginCleanupCannotRemoveIdenticalIndependentSnapshot() {
        val name = "reference-${UUID.randomUUID().toString().take(8)}"
        val record = c.pluginService.install(bundle(name, "same bytes"))
        val key = record.skills.single()
        val snapshots = app.filesDir.toPath().resolve("skills/snapshots")
        val directory = snapshots.resolve(".plugins/${key.ownerId}/${key.name}/${key.snapshotHash}/${key.name}")
        val staged = c.skillImportService.stageDirectory(directory)
        val independent = c.skillRepository.registerSnapshot(c.skillImportService.commit(staged, snapshots))
        c.skillImportService.discard(staged)
        try {
            c.skillRepository.setEnabled(independent, true, SkillEnablementScope.GLOBAL)
            c.pluginService.catalog.remove(record) // Commit removal, deliberately defer cleanup.
            c.pluginService.cleanupRetired()
            assertFalse(c.skillRepository.hasSnapshot(key))
            assertTrue(c.skillRepository.hasSnapshot(independent))
            assertEquals(
                "same bytes",
                c.skillRepository
                    .read(independent)
                    .body
                    .trim(),
            )
            assertThrows(IllegalArgumentException::class.java) { c.skillRepository.read(key) }
        } finally {
            c.pluginService
                .list()
                .filter { it.id == record.id }
                .forEach(c.pluginService::remove)
            c.skillRepository.removePermanentlyForPrivacy(independent)
        }
    }

    private fun bundle(
        name: String,
        body: String,
    ) = PluginPackageReader().parse(
        mapOf(
            "skills/$name/SKILL.md" to
                "---\nname: $name\ndescription: Reference fixture\n---\n$body".toByteArray(),
        ),
    )
}
