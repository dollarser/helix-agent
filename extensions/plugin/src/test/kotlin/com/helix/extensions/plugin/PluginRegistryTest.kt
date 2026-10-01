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
