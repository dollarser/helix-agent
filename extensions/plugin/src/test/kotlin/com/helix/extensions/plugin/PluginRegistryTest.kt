package com.helix.extensions.plugin

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolImplementationRegistry
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
        val implementations = ToolImplementationRegistry()
        val plugins = PluginRegistry(tools, implementations)
        val plugin = plugin("mobile-use")

        plugins.register(plugin)

        val descriptor = tools.all().single()
        assertEquals(ToolOrigin.PluginOrigin("mobile-use", "0.1.0", "mobile-use"), descriptor.origin)
        assertEquals("mobile-use", plugins.all().single().name)
    }

    @Test
    fun duplicatePluginIdAndMismatchedOriginFailClosed() {
        val tools = ToolRegistry()
        val implementations = ToolImplementationRegistry()
        val plugins = PluginRegistry(tools, implementations)
        plugins.register(plugin("mobile-use"))
        assertThrows(IllegalArgumentException::class.java) { plugins.register(plugin("mobile-use")) }

        val bad = plugin("other", descriptorOrigin = ToolOrigin.PluginOrigin("wrong", "0.1.0", "other"))
        assertThrows(IllegalArgumentException::class.java) { plugins.register(bad) }
    }

    @Test
    fun orphanImplementationCollisionIsRejectedBeforeAnyDescriptorIsPublished() {
        val tools = ToolRegistry()
        val implementations = ToolImplementationRegistry()
        val plugins = PluginRegistry(tools, implementations)
        val plugin = plugin("mobile-use")
        val binding = plugin.tools().single()
        implementations.register(binding.descriptor, binding.executor)

        assertThrows(IllegalArgumentException::class.java) { plugins.register(plugin) }
        assertEquals(emptyList<ToolDescriptor>(), tools.all())
        assertEquals(emptyList<PluginManifest>(), plugins.all())
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

            override fun tools() = listOf(PluginToolBinding(descriptor, executor))
        }
    }
}
