package com.helix.extensions.plugin

import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillImportService
import com.helix.extensions.skills.SkillRepository
import com.helix.extensions.skills.UserSkillActivation
import com.helix.tools.framework.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.nio.file.Files

class ImportedSkillActivationIdentityTest {
    @Test fun sameContentOwnedByAnotherPackageCannotReplaceAcceptedActivation() {
        val root = Files.createTempDirectory("owned-activation-identity")
        val source = Files.createDirectory(root.resolve("guide"))
        Files.writeString(source.resolve("SKILL.md"), "---\nname: guide\ndescription: Guide\n---\nExact body")
        val importer = SkillImportService(root.resolve("staging"))
        val snapshots = root.resolve("snapshots")
        val first = importer.commit(importer.stageDirectory(source), snapshots, "conn-first")
        val second = importer.commit(importer.stageDirectory(source), snapshots, "conn-second")
        var accepted = emptyList<UserSkillActivation>()
        var selected = "conn-first"
        val repository =
            SkillRepository(
                snapshots,
                root.resolve("state"),
                root.resolve("trash"),
                builtIns = emptyList(),
                userActivations = { _, _ -> accepted },
                pluginAvailable = { key, _ -> key.ownerId == selected },
            )
        val firstKey = repository.registerSnapshot(first)
        val secondKey = repository.registerSnapshot(second)
        assertNotEquals(firstKey, secondKey)
        assertEquals(firstKey.snapshotHash, secondKey.snapshotHash)
        accepted = listOf(repository.prepareUserActivation(firstKey, "session", "argument"))
        val registry = PluginRegistry(ToolRegistry(), MemoryNativePluginCatalog())
        val contribution =
            registry
                .contentContributions(repository, "session", "turn")
                .filterIsInstance<PluginContentContribution.ActivatedSkill>()
                .single()
        assertEquals(firstKey, contribution.key)
        selected = "conn-second"
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            registry.contentContributions(repository, "session", "turn")
        }
    }

    @Test fun importedBodyKeepsAcceptedTreeHashInsteadOfManifestHash() {
        val root = Files.createTempDirectory("imported-activation-identity")
        val source = Files.createDirectory(root.resolve("guide"))
        Files.writeString(source.resolve("SKILL.md"), "---\nname: guide\ndescription: Guide\n---\nExact body")
        Files.createDirectory(source.resolve("references"))
        Files.writeString(source.resolve("references/detail.txt"), "Bound resource")
        val importer = SkillImportService(root.resolve("staging"))
        val snapshot = importer.commit(importer.stageDirectory(source), root.resolve("snapshots"))
        var accepted = emptyList<UserSkillActivation>()
        val repository =
            SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state"),
                root.resolve("trash"),
                builtIns = emptyList(),
                userActivations = { _, _ -> accepted },
            )
        val key = repository.registerSnapshot(snapshot)
        repository.setEnabled(key, true, SkillEnablementScope.SESSION, "session")
        accepted = listOf(repository.prepareUserActivation(key, "session", "argument"))
        assertNotEquals(key.snapshotHash, repository.inspect(key).catalogEntry.contentHash)
        val registry = PluginRegistry(ToolRegistry(), MemoryNativePluginCatalog())
        val contribution =
            registry
                .contentContributions(repository, "session", "turn")
                .filterIsInstance<PluginContentContribution.ActivatedSkill>()
                .single()
        assertEquals(key, contribution.key)
        assertEquals("Exact body", contribution.body.trim())
        assertEquals("argument", contribution.acceptedArguments)
    }
}
