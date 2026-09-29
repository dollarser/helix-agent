package com.helix.extensions.plugin

import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry

/** Host-native component of an Agent Plugin. It contributes capabilities, never another AgentLoop. */
interface HelixPlugin {
    val manifest: PluginManifest

    fun tools(): List<com.helix.tools.framework.ToolBinding>
}

/**
 * Minimal bundled-plugin registry. P0 intentionally has no arbitrary code loading or package
 * lifecycle; it gives host-known runtimes a single provenance-checked path into the existing tool
 * registries. Dispatcher/Policy/Approval/Audit remain the only execution path after registration.
 */
class PluginRegistry(
    private val toolRegistry: ToolRegistry,
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
        require(bindings.all { it.descriptor.origin == expectedOrigin }) { "plugin provenance mismatch" }
        toolRegistry.registerBatch(
            bindings.map {
                com.helix.tools.framework
                    .ToolBinding(it.descriptor, it.executor, "${manifest.version}:$runtimeId")
            },
        )
        installed[manifest.name] = manifest
        return manifest
    }

    @Synchronized
    fun all(): List<PluginManifest> = installed.values.toList()

    @Synchronized
    fun find(pluginId: String): PluginManifest? = installed[pluginId]
}
