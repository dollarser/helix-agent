package com.helix.extensions.plugin

import com.helix.extensions.skills.SkillDocument
import com.helix.extensions.skills.SkillLoader
import com.helix.extensions.skills.SkillRepository
import com.helix.extensions.skills.UserSkillActivation
import com.helix.tools.framework.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class NativeSkillOwnerIdentityTest {
    @Test fun identicalNativeSkillBytesKeepSelectionAndAcceptedOwnerDistinct() {
        val catalog = MemoryNativePluginCatalog()
        val registry = PluginRegistry(ToolRegistry(), catalog)
        val document = SkillLoader().loadBuiltIn("---\nname: guide\ndescription: Guide\n---\nSame body", "guide")
        registry.register(plugin("first", document))
        registry.register(plugin("second", document))
        catalog.selections["session" to "first"] = "first-selection"
        val root = Files.createTempDirectory("native-owner-identity")
        var accepted = emptyList<UserSkillActivation>()
        val repository =
            SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state"),
                root.resolve("trash"),
                builtIns = emptyList(),
                sourceAvailable = registry::skillAvailable,
                contributedDocuments = registry::skillDocuments,
                userActivations = { _, _ -> accepted },
            )
        val first = repository.list().single { it.key.ownerId == "first" }.key
        val second = repository.list().single { it.key.ownerId == "second" }.key
        assertNotEquals(first, second)
        assertEquals(first.snapshotHash, second.snapshotHash)
        Files.list(root.resolve("snapshots")).use { assertEquals(0L, it.count()) }
        assertEquals(document.body, repository.read(first, "session").body)
        assertThrows(IllegalArgumentException::class.java) { repository.read(second, "session") }
        assertTrue(repository.listSelectable("session").isEmpty())
        assertFalse(registry.ownsSkill(first.copy(ownerId = "")))
        accepted = listOf(repository.prepareUserActivation(first, "session", "original argument"))
        val activation =
            registry
                .contentContributions(repository, "session", "turn")
                .filterIsInstance<PluginContentContribution.ActivatedSkill>()
                .single()
        assertEquals(first, activation.key)
        assertEquals(listOf("first"), activation.owners.map { it.pluginId })
        catalog.selections.remove("session" to "first")
        catalog.selections["session" to "second"] = "second-selection"
        assertEquals(document.body, repository.read(second, "session").body)
        assertThrows(
            IllegalArgumentException::class.java,
        ) { registry.contentContributions(repository, "session", "turn") }
        assertEquals(first, accepted.single().key)
        registry.setEnabled("first", false)
        assertEquals(document.body, repository.read(second, "session").body)
    }

    private fun plugin(
        id: String,
        document: SkillDocument,
    ): HelixPlugin =
        object : HelixPlugin {
            override val manifest =
                PluginManifestReader.parse(
                    """
                    {"${'$'}schema":"${PluginManifest.AGENT_PLUGINS_V1_SCHEMA}","name":"$id",
                     "version":"1","description":"Fixture","extensions":{"com.helix.agent":{"runtime":"$id"}}}
                    """.trimIndent().toByteArray(),
                )
            override val skills = listOf(document)

            override fun tools() = emptyList<com.helix.tools.framework.ToolBinding>()
        }
}
