package com.helix.app.application.context

import com.helix.app.mcp.ToolSourceCatalogEntry
import com.helix.app.mcp.ToolSourceCatalogSnapshot
import com.helix.core.agent.PromptContentRetention
import com.helix.core.agent.PromptRegistry
import com.helix.core.agent.TrustLevel
import com.helix.extensions.plugin.NativePluginState
import com.helix.extensions.plugin.PluginContentContribution
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptContentContributionsTest {
    private val owner = NativePluginState("plugin-a", "runtime-a", "1", "a".repeat(64), true, 7)
    private val key = SkillKey(SkillSource.BUILT_IN, "fixture", "b".repeat(64))

    @Test
    fun activationKeepsBodyAndAcceptedArgumentsAtomicAndUntrusted() {
        val contribution = PluginContentContribution.ActivatedSkill(key, "BODY-PRIVATE", "ARG-PRIVATE", listOf(owner))
        val section = resolve(listOf(contribution), emptySet()).single()
        assertEquals(PromptContentRetention.REQUIRED, section.retention)
        assertEquals(TrustLevel.UNTRUSTED, section.trust)
        assertTrue(section.content.contains("BODY-PRIVATE"))
        assertTrue(section.content.contains("ARG-PRIVATE"))
        assertEquals(
            "runtime-a",
            section.identity!!
                .owners
                .single()
                .runtimeId,
        )
        assertEquals(
            7L,
            section.identity!!
                .owners
                .single()
                .revision,
        )
        assertNull(section.readReference)
    }

    @Test
    fun directoryReadReferenceRequiresActualCallableToolAndExactVersion() {
        val contribution = PluginContentContribution.SkillCatalog(key, "description", owner)
        val absent = resolve(listOf(contribution), emptySet()).single()
        assertNull(absent.readReference)
        val admitted = resolve(listOf(contribution), setOf("skills.read")).single()
        assertEquals(PromptContentRetention.OPTIONAL, admitted.retention)
        assertEquals("skills.read", admitted.readReference!!.tool)
        assertTrue(admitted.readReference!!.argumentsJson.contains(key.snapshotHash))
        assertEquals(absent.contentHash, admitted.contentHash)
    }

    @Test
    fun toolDirectoryDoesNotAdvertiseAnUnavailableSearchTool() {
        val catalog = ToolSourceCatalogSnapshot(listOf(ToolSourceCatalogEntry("mcp:fixture", listOf("files"), "r1")), 2)

        fun content(tools: Set<String>): String =
            PromptRegistry()
                .also { registry ->
                    PromptContentContributions.sections(emptyList(), catalog, null, tools).forEach(registry::register)
                }.assemble()
        assertFalse(content(emptySet()).contains("tools.search"))
        assertTrue(content(setOf("tools.search")).contains("Use tools.search for callable schemas"))
        assertTrue(content(emptySet()).contains("omitted 2 rows"))
    }

    @Test fun identicalCatalogsKeepAllOwnersWithoutRepeatingMetadata() {
        val second = owner.copy(pluginId = "plugin-b", runtimeId = "runtime-b", revision = 8)
        val items =
            listOf(
                PluginContentContribution.SkillCatalog(key, "shared description"),
                PluginContentContribution.SkillCatalog(key, "shared description", owner),
                PluginContentContribution.SkillCatalog(key, "shared description", second),
            )
        val section = resolve(items, setOf("skills.read")).single()
        assertEquals(listOf("plugin-a", "plugin-b"), section.identity!!.owners.map { it.id })
        assertEquals(listOf(7L, 8L), section.identity!!.owners.map { it.revision })
        assertEquals(key.snapshotHash, section.identity!!.revision)
        assertTrue(section.readReference!!.argumentsJson.contains(key.snapshotHash))
        assertEquals(TrustLevel.UNTRUSTED, section.trust)
        assertEquals(PromptContentRetention.OPTIONAL, section.retention)
        assertEquals(2, resolve(items + items.first().copy(description = "different facts"), emptySet()).size)
        assertEquals(
            2,
            resolve(items + items.first().copy(key = key.copy(snapshotHash = "c".repeat(64))), emptySet()).size,
        )
        val revoked = resolve(listOf(items.first(), items.last()), emptySet()).single()
        assertEquals(listOf("plugin-b"), revoked.identity!!.owners.map { it.id })
        assertNull(revoked.readReference)
    }

    @Test fun discoveryGuidanceIsNotRepeatedForEveryToolSource() {
        for (omitted in listOf(0, 2)) {
            val catalog =
                ToolSourceCatalogSnapshot(
                    (0..7).map { ToolSourceCatalogEntry("source-$it", listOf("category-$it"), "revision-$it") },
                    omitted,
                )
            val sections = PromptContentContributions.sections(emptyList(), catalog, null, setOf("tools.search"))
            val content = PromptRegistry().also { registry -> sections.forEach(registry::register) }.assemble()
            assertEquals(1, Regex("Use tools.search for callable schemas").findAll(content).count())
            catalog.entries.forEach { entry -> assertTrue(content.contains(entry.source)) }
            assertTrue(sections.all { it.trust == TrustLevel.UNTRUSTED })
        }
    }

    private fun resolve(
        contributions: List<PluginContentContribution>,
        tools: Set<String>,
    ) = PromptRegistry()
        .also { registry ->
            PromptContentContributions.sections(contributions, null, null, tools).forEach(registry::register)
        }.resolve()
}
