package com.helix.app.plugin

import androidx.test.core.app.ApplicationProvider
import com.helix.app.HelixApplication
import com.helix.app.infrastructure.agent.ExpertProfileSnapshotCodec
import com.helix.core.storage.repository.ExpertDefinition
import com.helix.core.storage.repository.ExpertProfile
import com.helix.extensions.plugin.PluginPackageReader
import com.helix.extensions.skills.SkillEnablementScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** Actual Room, plugin catalog and SkillRepository, with only owned synthetic dependencies. No network. */
class ExpertContributionsDeviceTest {
    @Test
    @Suppress("LongMethod") // One owned graph verifies scope, revocation and cleanup.
    fun selectedExpertContributesWithoutOverwritingManualChoicesOrSnapshots() =
        runBlocking {
            val app = ApplicationProvider.getApplicationContext<HelixApplication>()
            val c = app.awaitAppContainer()
            withContext(Dispatchers.IO) {
                val service = c.pluginService
                val storage = c.storage
                // Recover only prior interrupted instances of this test's private packages.
                service
                    .list()
                    .filter { record ->
                        listOf("manual", "linked", "duplicate").any { kind ->
                            record.identity.startsWith("fixture:$kind:") &&
                                record.name.startsWith("expert-${if (kind == "duplicate") "linked" else kind}-")
                        }
                    }.forEach(service::remove)
                val token = UUID.randomUUID().toString().take(8)
                val session = "expert-a-$token"
                val peer = "expert-b-$token"
                storage.sessions.create(session, "Expert synthetic A", null, null, 1)
                storage.sessions.create(peer, "Expert synthetic B", null, null, 1)
                // Isolate only these owned fixtures from the user's new-conversation defaults.
                listOf(session, peer).forEach { fixture ->
                    service.catalog.selected(fixture).forEach { service.catalog.select(fixture, it, false) }
                }
                val installed = mutableListOf<InstalledPlugin>()
                val id = "expert-$token"
                try {
                    val manual =
                        service
                            .install(bundle("expert-manual-$token"), "fixture:manual:$token")
                            .also(installed::add)
                    val linked =
                        service
                            .install(bundle("expert-linked-$token"), "fixture:linked:$token")
                            .also(installed::add)
                    val native = service.list().first { it.native != null && it.enabled }
                    val key = linked.skills.single()
                    service.catalog.select(session, manual.id, true)
                    assertFalse(c.skillRepository.listForModel(session, null).any { it.key == key })
                    storage.sessionExperts.setForSession(
                        peer,
                        ExpertProfile(
                            "skill-only-$token",
                            "Skill only",
                            "Synthetic",
                            listOf(expertSkillReference(key)),
                        ),
                        1,
                    )
                    assertTrue(service.catalog.effectiveSelected(peer).isEmpty())
                    assertFalse(c.skillRepository.listForModel(peer, null).any { it.key == key })
                    assertThrows(IllegalArgumentException::class.java) { c.skillRepository.read(key, peer) }
                    storage.sessionExperts.clearForSession(peer, 2)
                    val profile =
                        ExpertProfile(
                            id,
                            "Synthetic expert",
                            "Use evidence and respect permissions.",
                            listOf(expertSkillReference(key)),
                            listOf(
                                expertPluginReference(manual),
                                expertPluginReference(linked),
                                expertPluginReference(native),
                            ),
                        )
                    storage.experts.save(ExpertDefinition(profile, "Synthetic configuration"))
                    storage.sessionExperts.setForSession(session, profile, 2)
                    val selectedProfile = requireNotNull(storage.sessionExperts.forSession(session))
                    val frozen = ExpertProfileSnapshotCodec.encode(selectedProfile)
                    val nativeSelection = service.catalog.selectionId(session, requireNotNull(native.native).pluginId)
                    assertTrue(nativeSelection != null)
                    assertEquals(setOf(manual.id), service.catalog.selected(session))
                    assertTrue(linked.id in service.catalog.effectiveSelected(session))
                    assertFalse(linked.id in service.catalog.effectiveSelected(peer))
                    assertTrue(c.skillRepository.listForModel(session, null).any { it.key == key })
                    assertTrue(
                        c.skillRepository
                            .read(key, session)
                            .body
                            .contains("Synthetic dependency only"),
                    )
                    assertThrows(IllegalArgumentException::class.java) { c.skillRepository.read(key, peer) }

                    storage.experts.save(
                        ExpertDefinition(
                            profile.copy(
                                instruction = "Edited global",
                                recommendedSkillIds = emptyList(),
                            ),
                        ),
                    )
                    assertEquals(profile, storage.sessionExperts.forSession(session))
                    assertEquals(profile, ExpertProfileSnapshotCodec.decode(frozen))
                    val rejected =
                        assertThrows(IllegalArgumentException::class.java) {
                            c.skillRepository.setEnabled(key, false, SkillEnablementScope.SESSION, session)
                        }
                    assertEquals("PLUGIN_OWNED_SKILL", rejected.message)
                    assertTrue(
                        c.skillRepository
                            .read(key, session)
                            .body
                            .contains("Synthetic dependency only"),
                    )
                    service.setEnabled(linked.id, false)
                    assertThrows(IllegalArgumentException::class.java) { c.skillRepository.read(key, session) }
                    service.setEnabled(linked.id, true)
                    storage.sessionExperts.clearForSession(session, 3)
                    assertEquals(setOf(manual.id), service.catalog.effectiveSelected(session))
                    assertNull(service.catalog.selectionId(session, requireNotNull(native.native).pluginId))
                    storage.sessionExperts.setForSession(session, profile, 4)
                    val renewed = service.catalog.selectionId(session, requireNotNull(native.native).pluginId)
                    assertNotEquals(nativeSelection, renewed)

                    val duplicate =
                        service
                            .install(bundle("expert-linked-$token"), "fixture:duplicate:$token")
                            .also(installed::add)
                    assertEquals(linked.hash, duplicate.hash)
                    assertFalse(linked.id in service.catalog.expertSelected(session))
                    assertFalse(duplicate.id in service.catalog.expertSelected(session))
                    storage.sessionExperts.setForSession(
                        peer,
                        profile.copy(recommendedConnectorIds = listOf("package:missing")),
                        5,
                    )
                    assertTrue(service.catalog.effectiveSelected(peer).isEmpty())
                } finally {
                    storage.experts.remove(id)
                    storage.deleteSessionPermanently(session)
                    storage.deleteSessionPermanently(peer)
                    installed.reversed().forEach { created ->
                        service.list().singleOrNull { it.id == created.id }?.let(service::remove)
                    }
                }
            }
        }

    private fun bundle(name: String) =
        PluginPackageReader().parse(
            mapOf(
                ".codex-plugin/plugin.json" to """{"name":"$name"}""".toByteArray(),
                "skills/$name/SKILL.md" to
                    ("---\nname: $name\ndescription: Fixture\n---\n" + "Synthetic dependency only.").toByteArray(),
            ),
        )
}
