package com.helix.extensions.plugin

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolBinding
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class PluginRegistryTest {
    private fun skill(
        name: String,
        body: String = "Observe before acting",
    ) = com.helix.extensions.skills.SkillLoader().loadBuiltIn(
        "---\nname: $name\ndescription: Workflow guide\n---\n$body",
        name,
    )

    @Test fun zeroToolPluginContentUsesRepositoryAndRevocationCannotBeBypassed() {
        val catalog = MemoryNativePluginCatalog()
        val registry = PluginRegistry(ToolRegistry(), catalog)
        val base = plugin("workflow")
        registry.register(
            object : HelixPlugin {
                override val manifest = base.manifest
                override val skills = listOf(skill("guide"))

                override fun tools() = emptyList<ToolBinding>()
            },
        )
        val root =
            java.nio.file.Files
                .createTempDirectory("zero-tool-skills")
        val repository =
            com.helix.extensions.skills.SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state"),
                root.resolve("trash"),
                contributedDocuments = registry::skillDocuments,
            )
        val key = repository.list().single { it.key.name == "guide" }.key
        assertEquals(true, registry.ready("workflow"))
        assertEquals(false, repository.list("session").single { it.key == key }.enabled)
        catalog.selections["session" to "workflow"] = "selection"
        assertEquals("Observe before acting", repository.read(key, "session").body.trim())
        catalog.selections.clear()
        repository.setEnabled(key, true, com.helix.extensions.skills.SkillEnablementScope.SESSION, "session")
        assertThrows(IllegalArgumentException::class.java) { repository.read(key, "session") }
        assertEquals("Observe before acting", repository.inspect(key).body.trim())
    }

    @Test fun losingBackendBlocksNewSelectionWithoutRevokingAnExistingSelectionOrPublishedTools() {
        val catalog = MemoryNativePluginCatalog()
        val tools = ToolRegistry()
        val registry = PluginRegistry(tools, catalog)
        val base = plugin("fixture")
        var error: String? = null
        registry.register(
            object : HelixPlugin by base {
                override fun configurationError() = error
            },
        )
        registry.validateSelection("fixture")
        catalog.selections["session" to "fixture"] = "original-selection"
        val before = tools.snapshot()
        error = "MOBILE_USE_BACKEND_UNAVAILABLE"
        assertEquals(
            error,
            assertThrows(IllegalStateException::class.java) {
                registry.validateSelection("fixture")
            }.message,
        )
        assertEquals("original-selection", catalog.selections["session" to "fixture"])
        assertEquals(before, tools.snapshot())
        assertEquals(true, registry.ready("fixture"))
        error = null
        registry.validateSelection("fixture")
        assertEquals("original-selection", catalog.selections["session" to "fixture"])
    }

    @Test fun approvalPresentationRequiresTheSelectedCurrentNativePlugin() {
        val catalog = MemoryNativePluginCatalog()
        val registry = PluginRegistry(ToolRegistry(), catalog)
        val base = plugin("fixture")
        val shown = mutableListOf<PluginTaskIdentity>()
        val native =
            object : HelixPlugin by base, PluginApprovalPresentation {
                override fun showApproval(task: PluginTaskIdentity) {
                    shown += task
                }
            }
        registry.register(native)
        val descriptor = base.tools().single().descriptor
        val original = PluginTaskIdentity("a", "turn-a")
        registry.presentApproval(descriptor, original)
        assertEquals(emptyList<PluginTaskIdentity>(), shown)
        catalog.selections["a" to "fixture"] = "selection"
        registry.presentApproval(descriptor, original)
        assertEquals(listOf(original), shown)
        registry.presentApproval(descriptor, PluginTaskIdentity("b", "turn-b"))
        registry.presentApproval(descriptor.copy(description = "stale"), original)
        registry.setEnabled("fixture", false)
        registry.presentApproval(descriptor, original)
        assertEquals(listOf(original), shown)
    }

    @Test fun contextComesOnlyFromSelectedPublishedPlugins() {
        val catalog = MemoryNativePluginCatalog()
        val registry = PluginRegistry(ToolRegistry(), catalog)
        val base = plugin("fixture")
        val scope =
            com.helix.core.policy.AutomationSessionScope(
                setOf("example.app"),
                emptySet(),
                0,
                java.time.Instant.MAX,
                false,
                "config",
            )
        val contribution =
            object : HelixPlugin by base {
                override val preferredTools = setOf("fixture.fixture")
                override val skills = listOf(skill("guide"))
                override val dataOrigin = com.helix.core.policy.DataOrigin.ACCESSIBILITY

                override fun imageScope(sessionId: String) = scope

                override fun scopeFor(
                    descriptor: ToolDescriptor,
                    sessionId: String,
                ) = scope
            }
        registry.register(contribution)
        val root =
            java.nio.file.Files
                .createTempDirectory("plugin-skills")
        val repository =
            com.helix.extensions.skills.SkillRepository(
                root.resolve("snapshots"),
                root.resolve("state"),
                root.resolve("trash"),
                contributedDocuments = registry::skillDocuments,
            )
        val descriptor = base.tools().single().descriptor
        assertEquals(null, registry.executionContext(descriptor, "session"))
        assertEquals(emptySet<String>(), registry.preferredTools("session"))
        assertEquals(emptyList<com.helix.core.policy.UserScope>(), registry.imageScopes("session"))
        catalog.selections["session" to "fixture"] = "selection"
        assertEquals(scope, registry.executionContext(descriptor, "session")?.scope)
        assertEquals(setOf("fixture.fixture"), registry.preferredTools("session"))
        assertEquals(listOf(scope), registry.imageScopes("session"))
        assertEquals("Observe before acting", registry.instructions(repository, "session")?.trim())
        val key = repository.list("session").single { it.key.name == "guide" }.key
        repository.setEnabled(key, false, com.helix.extensions.skills.SkillEnablementScope.SESSION, "session")
        assertEquals(null, registry.instructions(repository, "session"))
        repository.setEnabled(key, true, com.helix.extensions.skills.SkillEnablementScope.SESSION, "session")
        registry.setEnabled("fixture", false)
        assertEquals(null, registry.executionContext(descriptor, "session"))
        assertEquals(null, registry.instructions(repository, "session"))
        assertEquals(emptySet<String>(), registry.preferredTools("session"))
        registry.setEnabled("fixture", true)
        catalog.selections.clear()
        assertEquals(null, registry.instructions(repository, "session"))
        assertEquals(null, registry.executionContext(descriptor, "session"))
    }

    @Test fun selectionValidationDoesNotMutateRuntimeOrCatalog() {
        val catalog = MemoryNativePluginCatalog()
        val registry = PluginRegistry(ToolRegistry(), catalog)
        val base = plugin("fixture")
        registry.register(
            object : HelixPlugin by base {
                override fun configurationError() = "NOT_CONFIGURED"
            },
        )
        val before = catalog.states.toMap()
        assertEquals("NOT_CONFIGURED", registry.selectionError("fixture"))
        assertEquals("PLUGIN_HOST_COMPONENT_UNAVAILABLE", registry.selectionError("missing"))
        assertEquals(
            "NOT_CONFIGURED",
            assertThrows(IllegalStateException::class.java) {
                registry.validateSelection("fixture")
            }.message,
        )
        assertEquals(before, catalog.states)
        assertEquals(emptyMap<Pair<String, String>, String>(), catalog.selections)
    }

    @Test fun disabledPluginContentsRemainInspectableWithoutPublishingTools() {
        val tools = ToolRegistry()
        val registry = PluginRegistry(tools, MemoryNativePluginCatalog())
        val original = plugin("mobile-use")
        val skill = skill("android-ui-task")
        registry.register(
            object : HelixPlugin {
                override val manifest = original.manifest
                override val skills = listOf(skill)

                override fun tools() = original.tools()
            },
        )
        registry.setEnabled("mobile-use", false)
        assertEquals(listOf(skill), registry.contents("mobile-use").skills)
        assertEquals(
            original.tools().map {
                it.descriptor.name.value
            },
            registry.contents("mobile-use").tools.map { it.first },
        )
        assertEquals(0, tools.all().size)
        assertEquals(false, registry.hasPublishedTools("mobile-use"))
    }

    @Test fun presentationProjectionTracksPublishedToolsWithoutGrantingAuthority() {
        val plugins = PluginRegistry(ToolRegistry(), MemoryNativePluginCatalog())
        assertEquals(false, plugins.hasPublishedTools("mobile-use"))
        plugins.register(plugin("mobile-use"))
        assertEquals(true, plugins.hasPublishedTools("mobile-use"))
        plugins.setEnabled("mobile-use", false)
        assertEquals(false, plugins.hasPublishedTools("mobile-use"))
        plugins.setEnabled("mobile-use", true)
        assertEquals(true, plugins.hasPublishedTools("mobile-use"))
    }

    @Test
    fun registersExactPluginProvenanceIntoExistingToolRegistries() {
        val tools = ToolRegistry()

        val plugins = PluginRegistry(tools, MemoryNativePluginCatalog())
        val plugin = plugin("mobile-use")

        plugins.register(plugin)

        val descriptor = tools.all().single()
        assertEquals(ToolOrigin.PluginOrigin("mobile-use", "0.1.0", "mobile-use"), descriptor.origin)
        assertEquals("mobile-use", plugins.all().single().name)
    }

    @Test
    fun duplicatePluginIdAndMismatchedOriginFailClosed() {
        val tools = ToolRegistry()

        val plugins = PluginRegistry(tools, MemoryNativePluginCatalog())
        plugins.register(plugin("mobile-use"))
        assertThrows(IllegalArgumentException::class.java) { plugins.register(plugin("mobile-use")) }

        val bad = plugin("other", descriptorOrigin = ToolOrigin.PluginOrigin("wrong", "0.1.0", "other"))
        assertThrows(IllegalArgumentException::class.java) { plugins.register(bad) }
    }

    @Test
    fun bindingCollisionKeepsOriginalAndDoesNotPublishPlugin() {
        val tools = ToolRegistry()

        val plugins = PluginRegistry(tools, MemoryNativePluginCatalog())
        val plugin = plugin("mobile-use")
        val binding = plugin.tools().single()
        tools.register(binding.descriptor, binding.executor)
        val before = tools.snapshot()

        assertThrows(IllegalArgumentException::class.java) { plugins.register(plugin) }
        assertEquals(before, tools.snapshot())
        assertEquals(emptyList<PluginManifest>(), plugins.all())
    }

    @Test fun reenableNeverRevivesTheOldBinding() {
        val tools = ToolRegistry()
        val store = MemoryNativePluginCatalog()
        val plugins = PluginRegistry(tools, store)
        plugins.register(plugin("mobile-use"))
        val descriptor = tools.all().single()
        val old = tools.resolveBinding(descriptor.name, descriptor.version)
        plugins.setEnabled("mobile-use", false)
        assertEquals(false, store.find("mobile-use")?.enabled)
        assertEquals(0, tools.all().size)
        plugins.setEnabled("mobile-use", true)
        val fresh = tools.resolveBinding(descriptor.name, descriptor.version)
        org.junit.Assert.assertNotEquals(old.ref, fresh.ref)
        assertEquals(3L, store.find("mobile-use")?.revision)
        assertEquals(null, tools.resolveBinding(old.ref))
    }

    @Test fun restartPreservesDurableDisabledState() {
        val store = MemoryNativePluginCatalog()
        val first = PluginRegistry(ToolRegistry(), store)
        first.register(plugin("mobile-use"))
        first.setEnabled("mobile-use", false)
        val tools = ToolRegistry()
        val reopened = PluginRegistry(tools, store)
        reopened.register(plugin("mobile-use"))
        assertEquals(false, reopened.ready("mobile-use"))
        assertEquals(0, tools.all().size)
        assertEquals("mobile-use", reopened.all().single().name)
    }

    @Test fun failedPublicationDoesNotChangeBindingsOrInstallation() {
        val store = MemoryNativePluginCatalog()
        val tools = ToolRegistry()
        val plugins = PluginRegistry(tools, store)
        plugins.register(plugin("mobile-use"))
        val original = tools.snapshot().single().ref
        store.failPublication = true
        assertThrows(IllegalStateException::class.java) { plugins.setEnabled("mobile-use", false) }
        assertEquals(original, tools.snapshot().single().ref)
        assertEquals(true, store.find("mobile-use")?.enabled)
        assertEquals(1L, store.find("mobile-use")?.revision)
    }

    @Test fun missingProjectionRequiresRepairAndDoesNotReviveOldBinding() {
        val tools = ToolRegistry()
        val plugins = PluginRegistry(tools, MemoryNativePluginCatalog())
        plugins.register(plugin("mobile-use"))
        val old = tools.snapshot().single().ref
        tools.replaceOwner(old.owner, emptyList())
        assertEquals(false, plugins.ready("mobile-use"))
        plugins.repair("mobile-use")
        assertEquals(true, plugins.ready("mobile-use"))
        assertEquals(null, tools.resolveBinding(old))
    }

    @Test fun repairDoesNotEnableDisabledPlugin() {
        val tools = ToolRegistry()
        val store = MemoryNativePluginCatalog()
        val plugins = PluginRegistry(tools, store)
        plugins.register(plugin("mobile-use"))
        plugins.setEnabled("mobile-use", false)
        plugins.repair("mobile-use")
        assertEquals(false, store.find("mobile-use")?.enabled)
        assertEquals(false, plugins.ready("mobile-use"))
        assertEquals(0, tools.snapshot().size)
    }

    @Test fun duplicateEnableDoesNotInvalidateAnActiveBinding() {
        val tools = ToolRegistry()
        val plugins = PluginRegistry(tools, MemoryNativePluginCatalog())
        plugins.register(plugin("mobile-use"))
        val original = tools.snapshot().single().ref
        plugins.setEnabled("mobile-use", true)
        assertEquals(original, tools.snapshot().single().ref)
    }

    private fun plugin(
        id: String,
        descriptorOrigin: ToolOrigin = ToolOrigin.PluginOrigin(id, "0.1.0", id),
    ): HelixPlugin {
        val manifest =
            PluginManifestReader.parse(
                """
                {
                  "${'$'}schema":"${PluginManifest.AGENT_PLUGINS_V1_SCHEMA}",
                  "name":"$id",
                  "version":"0.1.0",
                  "description":"fixture",
                  "extensions":{"com.helix.agent":{"runtime":"$id"}}
                }
                """.trimIndent().toByteArray(),
            )
        val descriptor =
            ToolDescriptor(
                name = ToolName("fixture.$id"),
                version = ToolVersion(1),
                description = "fixture tool",
                inputSchema = buildJsonObject {},
                outputSchema = buildJsonObject {},
                operationClass = ToolOperationClass.READ_ONLY,
                timeout = 5.seconds,
                maxOutputBytes = 1024,
                requiredCapabilities = emptySet(),
                idempotency = Idempotency.IDEMPOTENT,
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                origin = descriptorOrigin,
            )
        val executor =
            object : ToolExecutor {
                override fun execute(call: com.helix.tools.framework.ExecutableToolCall): ToolExecutorResult =
                    ToolExecutorResult.Completed(buildJsonObject {})
            }
        return object : HelixPlugin {
            override val manifest = manifest

            override fun tools() = listOf(ToolBinding(descriptor, executor))
        }
    }

    @Test fun collisionInTheLastPluginToolDoesNotPublishEarlierCandidates() {
        val tools = ToolRegistry()
        val plugins = PluginRegistry(tools, MemoryNativePluginCatalog())
        val source = plugin("mobile-use")
        val binding = source.tools().single()
        tools.register(binding.descriptor.copy(origin = ToolOrigin.BuiltInOrigin), binding.executor)
        val before = tools.snapshot()
        val batch =
            object : HelixPlugin {
                override val manifest = source.manifest

                override fun tools() =
                    listOf(
                        binding.copy(descriptor = binding.descriptor.copy(name = ToolName("new.tool"))),
                        binding,
                    )
            }
        assertThrows(IllegalArgumentException::class.java) { plugins.register(batch) }
        assertEquals(before, tools.snapshot())
        assertEquals(emptyList<PluginManifest>(), plugins.all())
    }
}
