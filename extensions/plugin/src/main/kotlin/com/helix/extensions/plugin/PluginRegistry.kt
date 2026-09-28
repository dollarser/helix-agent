package com.helix.extensions.plugin

import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry

/** One exact descriptor/executor pair contributed by a host-native plugin runtime. */
data class PluginToolBinding(
    val descriptor: ToolDescriptor,
    val executor: ToolExecutor,
)

/** Host-native component of an Agent Plugin. It contributes capabilities, never another AgentLoop. */
interface HelixPlugin {
    val manifest: PluginManifest

    fun tools(): List<PluginToolBinding>
}

/**
 * Minimal bundled-plugin registry. P0 intentionally has no arbitrary code loading or package
 * lifecycle; it gives host-known runtimes a single provenance-checked path into the existing tool
 * registries. Dispatcher/Policy/Approval/Audit remain the only execution path after registration.
 */
class PluginRegistry(
    private val toolRegistry: ToolRegistry,
    private val implementations: ToolImplementationRegistry,
) {
    private val installed = LinkedHashMap<String, PluginManifest>()

    @Synchronized
    fun register(plugin: HelixPlugin): PluginManifest {
        val manifest = plugin.manifest
        require(manifest.name !in installed) { "duplicate plugin id: ${manifest.name}" }
        val runtimeId =
            requireNotNull(manifest.helixRuntimeId) {
                "native plugin ${manifest.name} must declare " +
                    "extensions.${PluginManifest.HELIX_EXTENSION_NAMESPACE}.runtime"
            }
        val expectedOrigin = ToolOrigin.PluginOrigin(manifest.name, manifest.version, runtimeId)
        val bindings = plugin.tools()
        require(bindings.map { it.descriptor.name to it.descriptor.version }.distinct().size == bindings.size) {
            "plugin ${manifest.name} contributes duplicate tool contracts"
        }
        bindings.forEach { binding ->
            require(binding.descriptor.origin == expectedOrigin) {
                "plugin ${manifest.name} tool ${binding.descriptor.name.value} has mismatched provenance"
            }
            require(
                toolRegistry.all().none {
                    it.name == binding.descriptor.name && it.version == binding.descriptor.version
                },
            ) {
                "plugin ${manifest.name} collides with existing tool ${binding.descriptor.name.value} " +
                    "v${binding.descriptor.version.value}"
            }
            require(!implementations.contains(binding.descriptor.name, binding.descriptor.version)) {
                "plugin ${manifest.name} collides with existing implementation ${binding.descriptor.name.value} " +
                    "v${binding.descriptor.version.value}"
            }
        }
        bindings.forEach { binding ->
            toolRegistry.register(binding.descriptor)
            implementations.register(binding.descriptor, binding.executor)
        }
        installed[manifest.name] = manifest
        return manifest
    }

    @Synchronized
    fun all(): List<PluginManifest> = installed.values.toList()

    @Synchronized
    fun find(pluginId: String): PluginManifest? = installed[pluginId]
}
