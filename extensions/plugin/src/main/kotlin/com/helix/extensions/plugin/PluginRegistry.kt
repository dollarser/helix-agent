package com.helix.extensions.plugin

import com.helix.tools.framework.ToolBinding
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import com.helix.tools.framework.bindingOwner
import java.security.MessageDigest

/** Host-native component of an Agent Plugin. It contributes capabilities, never another AgentLoop. */
interface HelixPlugin {
    val manifest: PluginManifest
    val bundledSkills: List<PluginBundledSkill> get() = emptyList()

    fun tools(): List<com.helix.tools.framework.ToolBinding>
}

data class PluginBundledSkill(
    val name: String,
    val description: String,
    val content: String,
)

data class PluginContents(
    val tools: List<Pair<String, String>>,
    val skills: List<PluginBundledSkill>,
)

/** Trusted native factories only. Installation, enabled state and revision belong to the host catalog. */
class PluginRegistry(
    private val toolRegistry: ToolRegistry,
    private val catalog: NativePluginCatalog,
) {
    private val factories = LinkedHashMap<String, HelixPlugin>()

    fun contents(pluginId: String): PluginContents =
        synchronized(catalog.mutationLock) {
            val plugin = factories[pluginId]
            PluginContents(
                plugin?.tools().orEmpty().map { it.descriptor.name.value to it.descriptor.description },
                plugin?.bundledSkills.orEmpty(),
            )
        }

    /** Nonblocking presentation hint only; execution still resolves the authoritative binding. */
    fun hasPublishedTools(pluginId: String): Boolean =
        toolRegistry.snapshot().any { it.ref.owner == bindingOwner("plugin", pluginId) }

    fun register(plugin: HelixPlugin): PluginManifest =
        synchronized(catalog.mutationLock) {
            val manifest = plugin.manifest
            require(manifest.name !in factories) { "duplicate plugin id: ${manifest.name}" }
            publish(plugin, catalog.find(manifest.name)?.enabled ?: true)
            factories[manifest.name] = plugin
            manifest
        }

    fun setEnabled(
        pluginId: String,
        enabled: Boolean,
    ): NativePluginState =
        synchronized(catalog.mutationLock) {
            val current = requireNotNull(catalog.find(pluginId)) { "PLUGIN_NOT_INSTALLED" }
            val plugin = requireNotNull(factories[pluginId]) { "PLUGIN_HOST_COMPONENT_UNAVAILABLE" }
            if (current.enabled == enabled) current else publish(plugin, enabled)
        }

    fun ready(pluginId: String): Boolean =
        synchronized(catalog.mutationLock) {
            val current = catalog.find(pluginId)
            val factory = factories[pluginId]
            current?.enabled == true && factory != null && projectionMatches(current, factory)
        }

    private fun projectionMatches(
        state: NativePluginState,
        factory: HelixPlugin,
    ): Boolean {
        val expected = factory.tools().map { it.descriptor }.toSet()
        val published = toolRegistry.snapshot().filter { it.ref.owner == bindingOwner("plugin", state.pluginId) }
        return factory.manifest.version == state.version && factory.manifest.helixRuntimeId == state.runtimeId &&
            published.map { it.descriptor }.toSet() == expected &&
            published.all { it.binding.implementationRevision == "${state.revision}:${state.fingerprint}" }
    }

    /** Rebuilds the projection without changing durable enablement or granting permissions. */
    fun repair(pluginId: String): NativePluginState =
        synchronized(catalog.mutationLock) {
            val current = requireNotNull(catalog.find(pluginId)) { "PLUGIN_NOT_INSTALLED" }
            val factory = requireNotNull(factories[pluginId]) { "PLUGIN_HOST_COMPONENT_UNAVAILABLE" }
            publish(factory, current.enabled)
        }

    private fun publish(
        plugin: HelixPlugin,
        enabled: Boolean,
    ): NativePluginState {
        val manifest = plugin.manifest
        val runtimeId =
            requireNotNull(manifest.helixRuntimeId) {
                "native plugin ${manifest.name} must declare " +
                    "extensions.${PluginManifest.HELIX_EXTENSION_NAMESPACE}.runtime"
            }
        require(PortablePluginManifest.validName(manifest.name) && PortablePluginManifest.validName(runtimeId))
        require(manifest.version.isNotBlank()) { "NATIVE_PLUGIN_VERSION_REQUIRED" }
        val expectedOrigin = ToolOrigin.PluginOrigin(manifest.name, manifest.version, runtimeId)
        val bindings = plugin.tools()
        require(bindings.map { it.descriptor.name to it.descriptor.version }.distinct().size == bindings.size) {
            "plugin ${manifest.name} contributes duplicate tool contracts"
        }
        require(bindings.all { it.descriptor.origin == expectedOrigin }) { "plugin provenance mismatch" }
        val fingerprint =
            MessageDigest
                .getInstance("SHA-256")
                .digest(
                    (
                        listOf(manifest.name, manifest.version, runtimeId) +
                            bindings.map { it.descriptor.contractHash.hex }.sorted() +
                            plugin.bundledSkills
                                .sortedBy {
                                    it.name
                                }.map { "${it.name}:${it.description}:${it.content}" }
                    ).joinToString("\n")
                        .toByteArray(Charsets.UTF_8),
                ).joinToString("") { "%02x".format(it) }
        val previous = catalog.find(manifest.name)
        if (previous == null) {
            require(
                toolRegistry.snapshot().none { bindingOwner(it.descriptor.origin) == bindingOwner(expectedOrigin) },
            ) {
                "PLUGIN_UNOWNED_NATIVE_COLLISION"
            }
        }
        val state =
            NativePluginState(
                manifest.name,
                runtimeId,
                manifest.version,
                fingerprint,
                enabled,
                (previous?.revision ?: 0) + 1,
            )
        val published =
            if (enabled) {
                bindings.map { ToolBinding(it.descriptor, it.executor, "${state.revision}:$fingerprint") }
            } else {
                emptyList()
            }
        toolRegistry.replaceOwner(bindingOwner(expectedOrigin), published) {
            catalog.publish(state, previous?.revision)
        }
        return state
    }

    fun all(): List<PluginManifest> =
        synchronized(catalog.mutationLock) {
            factories.filterKeys { catalog.find(it) != null }.values.map { it.manifest }
        }

    fun find(pluginId: String): PluginManifest? =
        synchronized(catalog.mutationLock) {
            factories[pluginId]?.manifest?.takeIf { catalog.find(pluginId) != null }
        }
}
