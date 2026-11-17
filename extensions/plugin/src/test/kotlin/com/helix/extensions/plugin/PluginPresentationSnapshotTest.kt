package com.helix.extensions.plugin

import com.helix.extensions.skills.SkillEnablementScope
import com.helix.extensions.skills.SkillLoader
import com.helix.extensions.skills.SkillRepository
import com.helix.extensions.skills.UserSkillActivation
import com.helix.tools.framework.ToolRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PluginPresentationSnapshotTest {
    @Test fun displaySnapshotMatchesLiveProjectionWithoutRepeatedReadiness() {
        val fixture = Fixture()
        val expected = fixture.registry.contentContributions(fixture.skills, "s", "t")
        val before = fixture.readyCalls()
        val actual = fixture.capture()
        assertEquals(expected, actual)
        assertEquals(2, fixture.readyCalls() - before)
        assertEquals(
            setOf("first", "second"),
            actual
                .filterIsInstance<PluginContentContribution.SkillCatalog>()
                .mapNotNull { it.owner?.pluginId }
                .toSet(),
        )
    }

    @Test fun ownerSelectionAndIndependentEnablementStaySeparateAndRefreshNextRequest() {
        val fixture = Fixture()
        val first =
            fixture.skills
                .list("s")
                .single { it.key.ownerId == "first" }
                .key
        fixture.accepted = listOf(fixture.skills.prepareUserActivation(first, "s", "accepted"))
        val before = fixture.capture()
        assertEquals(
            listOf("first"),
            before
                .filterIsInstance<PluginContentContribution.ActivatedSkill>()
                .single()
                .owners
                .map { it.pluginId },
        )
        fixture.catalog.selections.remove("s" to "first")
        assertThrows(IllegalArgumentException::class.java) { fixture.capture() }
        assertThrows(IllegalArgumentException::class.java) { fixture.skills.read(first, "s", "t") }
        fixture.accepted = emptyList()
        val independent =
            fixture.skills
                .list("s")
                .single { it.key.ownerId.isEmpty() }
                .key
        fixture.skills.setEnabled(independent, false, SkillEnablementScope.SESSION, "s")
        val after = fixture.capture().filterIsInstance<PluginContentContribution.SkillCatalog>()
        assertEquals(setOf("second"), after.map { it.key.ownerId }.toSet())
        fixture.registry.setEnabled("second", false)
        assertTrue(fixture.capture().isEmpty())
        fixture.registry.setEnabled("second", true)
        assertEquals(
            setOf("second"),
            fixture
                .capture()
                .filterIsInstance<PluginContentContribution.SkillCatalog>()
                .map { it.key.ownerId }
                .toSet(),
        )
    }

    @Test fun changedNativeContentCannotReusePriorReadinessOrSkillKey() {
        val fixture = Fixture()
        val original = fixture.capture()
        val firstKey =
            original
                .filterIsInstance<PluginContentContribution.SkillCatalog>()
                .single { it.key.ownerId == "first" }
                .key
        fixture.first.body = "Edited body"
        assertFalse(fixture.registry.ready("first"))
        assertFalse(
            fixture
                .capture()
                .filterIsInstance<PluginContentContribution.SkillCatalog>()
                .any { it.key.ownerId == "first" },
        )
        fixture.registry.repair("first")
        val nextKey =
            fixture
                .capture()
                .filterIsInstance<PluginContentContribution.SkillCatalog>()
                .single { it.key.ownerId == "first" }
                .key
        assertTrue(firstKey != nextKey)
        assertThrows(IllegalArgumentException::class.java) { fixture.skills.read(firstKey, "s", "t") }
        assertEquals(
            "Edited body",
            fixture.skills
                .read(nextKey, "s", "t")
                .body
                .trim(),
        )
    }

    private class Fixture {
        val catalog = MemoryNativePluginCatalog()
        val registry = PluginRegistry(ToolRegistry(), catalog)
        val first = MutablePlugin("first")
        var accepted = emptyList<UserSkillActivation>()
        private val root = Files.createTempDirectory("presentation-snapshot")
        val skills: SkillRepository

        init {
            registry.register(first)
            registry.register(MutablePlugin("second"))
            catalog.selections["s" to "first"] = "one"
            catalog.selections["s" to "second"] = "two"
            skills =
                SkillRepository(
                    root.resolve("snapshots"),
                    root.resolve("state"),
                    root.resolve("trash"),
                    builtIns = listOf(first.skills.single()),
                    sourceAvailable = registry::skillAvailable,
                    contributedDocuments = registry::skillDocuments,
                    metadataLock = catalog.mutationLock,
                    userActivations = { _, _ ->
                        check(!Thread.holdsLock(catalog.mutationLock)) { "Activation lookup under metadata lock" }
                        accepted
                    },
                )
        }

        fun capture(): List<PluginContentContribution> {
            val activations = skills.presentationActivations("s", "t")
            return synchronized(catalog.mutationLock) {
                val native = registry.nativeContentSnapshot("s")
                presentationContributions(
                    native,
                    skills.presentationSnapshot("s", activations, native.skillPresentation()),
                )
            }
        }

        fun readyCalls() =
            registry.performance
                .snapshot()
                .filter { it.operation == PluginMeasurement.READINESS }
                .sumOf { it.calls }
    }

    private class MutablePlugin(
        id: String,
    ) : HelixPlugin {
        var body = "Same body"
        override val manifest =
            PluginManifestReader.parse(
                """{"${'$'}schema":"${PluginManifest.AGENT_PLUGINS_V1_SCHEMA}","name":"$id","version":"1",
            "description":"Fixture","extensions":{"com.helix.agent":{"runtime":"$id"}}}""".toByteArray(),
            )
        override val skills get() =
            listOf(
                SkillLoader().loadBuiltIn("---\nname: guide\ndescription: Guide\n---\n$body", "guide"),
            )

        override fun tools() = emptyList<com.helix.tools.framework.ToolBinding>()
    }
}
