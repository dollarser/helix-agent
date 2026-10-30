package com.helix.app.application.context

import com.helix.app.mcp.ToolSourceCatalogEntry
import com.helix.app.mcp.ToolSourceCatalogSnapshot
import com.helix.core.agent.HeadlessLoopFixture
import com.helix.core.agent.PromptRegistry
import com.helix.core.agent.TrustLevel
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.extensions.plugin.NativePluginState
import com.helix.extensions.plugin.PluginContentContribution
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Synthetic wire-payload comparison, not a real-model task quality benchmark. */
class PromptDisclosureLoopTest {
    @Suppress("LongMethod") // Ten bounded synthetic request projections share the same real loop setup.
    @Test
    fun tenCatalogAndActivationCasesReachTheMainLoopWithoutLosingFacts() =
        runBlocking {
            repeat(10) { index ->
                val key = SkillKey(SkillSource.BUILT_IN, "synthetic-$index", "a".repeat(64))
                val owners =
                    (0 until index % 3).map { owner ->
                        NativePluginState("plugin-$owner", "runtime-$owner", "1", "b".repeat(64), true, owner.toLong())
                    }
                val description = "Synthetic description $index"
                val contributions =
                    buildList {
                        add(PluginContentContribution.SkillCatalog(key, description))
                        owners.forEach { add(PluginContentContribution.SkillCatalog(key, description, it)) }
                        if (index % 2 == 0) {
                            val body = "Synthetic procedure $index"
                            val arguments = "Accepted arguments $index"
                            add(PluginContentContribution.ActivatedSkill(key, body, arguments, owners))
                        }
                    }
                val catalog =
                    ToolSourceCatalogSnapshot(
                        (0..index).map {
                            ToolSourceCatalogEntry("mcp:source-$it", listOf("synthetic-category-$it"), "revision-$it")
                        },
                        index % 2,
                    )
                val tools = if (index % 2 == 0) setOf("tools.search", "skills.read") else emptySet()
                val sections = PromptContentContributions.sections(contributions, catalog, null, tools)
                val registry = PromptRegistry()
                sections.forEach(registry::register)
                val snapshot = registry.resolveAndAssemble()
                assertTrue(snapshot.sections.all { it.trust == TrustLevel.UNTRUSTED })
                val actualOwners =
                    snapshot.sections
                        .flatMap { it.identity?.owners.orEmpty() }
                        .map { it.id }
                        .toSet()
                assertEquals(owners.map { it.pluginId }.toSet(), actualOwners)
                lateinit var loop: HeadlessLoopFixture
                loop =
                    HeadlessLoopFixture("disclosure-$index") {
                        val request = loop.requests.last()
                        val content = request.messages.filter { it.role == ModelRole.SYSTEM }.joinToString { it.text }
                        assertTrue(content.contains("Synthetic description $index"))
                        assertTrue(content.contains(key.snapshotHash))
                        catalog.entries.forEach { entry -> assertTrue(content.contains(entry.source)) }
                        if (index % 2 == 0) {
                            assertTrue(content.contains("Synthetic procedure $index"))
                            assertTrue(content.contains("Accepted arguments $index"))
                        }
                        listOf(ModelEvent.TextDelta("Synthetic projection verified"), ModelEvent.Completed("stop"))
                    }
                loop.journal.history.addAll(0, snapshot.modelMessages())
                loop.loop().runToolLoop("synthetic-session", loop.journal, "p", null, loop.control)
                assertEquals(1, loop.requests.size)
                assertTrue(loop.dispatched.isEmpty())
                val system =
                    loop.requests
                        .single()
                        .messages
                        .filter { it.role == ModelRole.SYSTEM }
                val bytes = system.sumOf { it.text.toByteArray().size }
                println(
                    "DISCLOSURE_METRIC case=$index bytes=$bytes " +
                        "modelCalls=${loop.requests.size} toolCalls=${loop.dispatched.size}",
                )
            }
        }
}
