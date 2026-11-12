package com.helix.app.plugin

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.core.storage.repository.ExpertProfile
import com.helix.extensions.plugin.PluginPackageReader
import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Equal bytes do not merge installation identities or lifecycle. */
class SharedSkillOwnershipDeviceTest {
    private val app get() = ApplicationProvider.getApplicationContext<HelixApplication>()
    private val container get() = app.appContainer
    private val plugins get() = container.pluginService
    private val skills get() = container.skillRepository

    @Test fun equalContentHasIndependentSelectionAndDistinctPhysicalSnapshots() =
        fixture { session, name, identity ->
            val plugin = plugins.install(bundle(name, "same bytes"), identity)
            val pluginKey = plugin.skills.single()
            val independentKey = installIndependent(pluginKey)
            assertEquals(pluginKey.snapshotHash, independentKey.snapshotHash)
            assertNotEquals(pluginKey, independentKey)
            assertEquals(plugin.id, pluginKey.ownerId)
            assertEquals("", independentKey.ownerId)
            plugins.catalog.select(session, plugin.id, true)
            assertFalse(skills.listSelectable(session).single { it.key == independentKey }.enabled)
            assertTrue(skills.listSelectable(session).none { it.key == pluginKey })
            assertEquals("same bytes", skills.read(pluginKey, session).body.trim())
            assertThrows(IllegalArgumentException::class.java) { skills.read(independentKey, session) }
            skills.setEnabled(independentKey, true, SkillEnablementScope.SESSION, session)
            assertNotEquals(
                skills.scriptSnapshot(pluginKey, session, null).directory,
                skills.scriptSnapshot(independentKey, session, null).directory,
            )
            plugins.catalog.select(session, plugin.id, false)
            assertThrows(IllegalArgumentException::class.java) { skills.read(pluginKey, session) }
            assertEquals("same bytes", skills.read(independentKey, session).body.trim())
        }

    @Test fun independentUninstallDoesNotTouchPluginAndReinstallStartsDisabled() =
        fixture { session, name, identity ->
            val plugin = plugins.install(bundle(name, "retained"), identity)
            val pluginKey = plugin.skills.single()
            val independentKey = installIndependent(pluginKey)
            skills.setEnabled(independentKey, true, SkillEnablementScope.SESSION, session)
            plugins.catalog.select(session, plugin.id, true)
            skills.uninstall(independentKey)
            assertFalse(skills.hasSnapshot(independentKey))
            assertTrue(skills.hasSnapshot(pluginKey))
            assertEquals("retained", skills.read(pluginKey, session).body.trim())
            assertThrows(IllegalArgumentException::class.java) { skills.uninstall(pluginKey) }
            assertEquals(independentKey, installIndependent(pluginKey))
            assertFalse(skills.listSelectable(session).single { it.key == independentKey }.enabled)
            assertThrows(IllegalArgumentException::class.java) { skills.read(independentKey, session) }
        }

    @Test fun pluginUpdateAndRemovalPreserveIndependentAndOtherPluginVersions() =
        fixture { session, name, identity ->
            val original = plugins.install(bundle(name, "old snapshot"), identity)
            val peer = plugins.install(bundle(name, "old snapshot"), "$identity:peer")
            val oldKey = original.skills.single()
            val peerKey = peer.skills.single()
            val independentKey = installIndependent(oldKey)
            skills.setEnabled(independentKey, true, SkillEnablementScope.SESSION, session)
            plugins.catalog.select(session, original.id, true)
            plugins.catalog.select(session, peer.id, true)
            val accepted = skills.prepareUserActivation(oldKey, session, "")
            val updated = plugins.install(bundle(name, "new snapshot"), identity, original.revision)
            val newKey = updated.skills.single()
            assertEquals(oldKey.ownerId, newKey.ownerId)
            assertNotEquals(oldKey.snapshotHash, newKey.snapshotHash)
            assertEquals(oldKey, accepted.key)
            assertThrows(IllegalArgumentException::class.java) { skills.read(accepted.key, session) }
            assertEquals("old snapshot", skills.read(independentKey, session).body.trim())
            assertEquals("old snapshot", skills.read(peerKey, session).body.trim())
            assertEquals("new snapshot", skills.read(newKey, session).body.trim())
            plugins.remove(updated)
            assertFalse(skills.hasSnapshot(newKey))
            assertTrue(skills.hasSnapshot(peerKey))
            assertTrue(skills.hasSnapshot(independentKey))
        }

    @Test fun expertReferencesDoNotSubstituteAnIdenticalPluginSkill() =
        fixture { session, name, identity ->
            val plugin = plugins.install(bundle(name, "expert fixture"), identity)
            val pluginKey = plugin.skills.single()
            val independentKey = installIndependent(pluginKey)
            bindExpert(session, skillIds = listOf(expertSkillReference(independentKey)))
            assertEquals("expert fixture", skills.read(independentKey, session).body.trim())
            assertThrows(IllegalArgumentException::class.java) { skills.read(pluginKey, session) }
            skills.uninstall(independentKey)
            assertThrows(IllegalArgumentException::class.java) { skills.read(pluginKey, session) }
            bindExpert(session, pluginIds = listOf(expertPluginReference(plugin)))
            assertEquals("expert fixture", skills.read(pluginKey, session).body.trim())
            assertTrue(skills.listSelectable(session).none { it.key == pluginKey })
            container.storage.sessionExperts.clearForSession(session, System.currentTimeMillis())
            assertThrows(IllegalArgumentException::class.java) { skills.read(pluginKey, session) }
        }

    private fun installIndependent(key: SkillKey): SkillKey {
        val snapshots = app.filesDir.toPath().resolve("skills/snapshots")
        val directory = snapshots.resolve(".plugins/${key.ownerId}/${key.name}/${key.snapshotHash}/${key.name}")
        val staged = container.skillImportService.stageDirectory(directory)
        return try {
            skills.registerSnapshot(container.skillImportService.commit(staged, snapshots))
        } finally {
            container.skillImportService.discard(staged)
        }
    }

    private fun bindExpert(
        session: String,
        skillIds: List<String> = emptyList(),
        pluginIds: List<String> = emptyList(),
    ) {
        container.storage.sessionExperts.setForSession(
            session,
            ExpertProfile("ownership-fixture", "Ownership fixture", "Synthetic test only", skillIds, pluginIds),
            System.currentTimeMillis(),
        )
    }

    private fun fixture(block: (String, String, String) -> Unit) {
        val id = UUID.randomUUID().toString()
        val session = "shared-skill-$id"
        val name = "shared-${id.take(8)}"
        val identity = "shared-skill-fixture:$id"
        container.storage.sessions.create(session, "Owner isolation fixture", null, null, 0)
        try {
            block(session, name, identity)
        } finally {
            container.storage.sessionExperts.clearForSession(session, System.currentTimeMillis())
            plugins.list().filter { it.identity.startsWith(identity) }.forEach(plugins::remove)
            skills.list().filter { it.key.name == name }.forEach { skills.uninstall(it.key) }
            container.storage.deleteSessionPermanently(session)
        }
    }

    private fun bundle(
        name: String,
        body: String,
    ) = PluginPackageReader().parse(
        mapOf(
            ".claude-plugin/plugin.json" to """{"name":"$name"}""".toByteArray(),
            "skills/$name/SKILL.md" to "---\nname: $name\ndescription: Ownership fixture\n---\n$body".toByteArray(),
        ),
    )
}
