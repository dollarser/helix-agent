package com.helix.extensions.plugin

import com.helix.extensions.skills.SkillDocument
import com.helix.tools.framework.ToolBinding
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import com.helix.tools.framework.bindingOwner
import java.security.MessageDigest

/** Host-native component of an Agent Plugin. It contributes capabilities, never another AgentLoop. */
interface HelixPlugin {
    val manifest: PluginManifest
    val skills: List<SkillDocument> get() = emptyList()

    val preferredTools: Set<String> get() = emptySet()
    val dataOrigin: com.helix.core.policy.DataOrigin get() = com.helix.core.policy.DataOrigin.WORKSPACE

    fun configurationError(): String? = null

    fun configurationSaved(): Boolean = true

    fun runtimeTarget(): String? = null

    fun scopeFor(
        descriptor: com.helix.tools.framework.ToolDescriptor,
        sessionId: String,
    ): com.helix.core.policy.UserScope? = null

    fun imageScope(sessionId: String): com.helix.core.policy.UserScope? = null

    fun tools(): List<com.helix.tools.framework.ToolBinding>
}

data class PluginContents(
    val tools: List<Pair<String, String>>,
    val skills: List<SkillDocument>,
)

/** Trusted native factories only. Installation, enabled state and revision belong to the host catalog. */
@Suppress("TooManyFunctions") // one registry owns publication and trusted native contributions
class PluginRegistry(
    private val toolRegistry: ToolRegistry,
    private val catalog: NativePluginCatalog,
) {
    private val factories = LinkedHashMap<String, HelixPlugin>()

    internal fun selectedRuntimes(sessionId: String): List<HelixPlugin> =
        synchronized(catalog.mutationLock) {
            factories
                .filter { (id, _) ->
                    ready(id) && catalog.selectionId(sessionId, id) != null
                }.values
                .toList()
        }

    internal fun runtime(descriptor: com.helix.tools.framework.ToolDescriptor): HelixPlugin? =
        synchronized(catalog.mutationLock) {
            val origin = descriptor.origin as? ToolOrigin.PluginOrigin ?: return@synchronized null
            factories[origin.pluginId]?.takeIf {
                hasPublishedTools(origin.pluginId) && it.tools().any { binding -> binding.descriptor == descriptor }
            }
        }

    fun presentApproval(
        descriptor: com.helix.tools.framework.ToolDescriptor,
        task: PluginTaskIdentity,
    ) {
        val presentation =
            synchronized(catalog.mutationLock) {
                val origin = descriptor.origin as? ToolOrigin.PluginOrigin
                if (origin == null || catalog.selectionId(task.conversationId, origin.pluginId) == null) {
                    null
                } else {
                    runtime(descriptor) as? PluginApprovalPresentation
                }
            }
        presentation?.showApproval(task)
    }

    fun validateSelection(pluginId: String) {
        selectionError(pluginId)?.let { error(it) }
    }

    /** Read-only preflight shared by selection UI and the authoritative write path. */
    fun selectionError(pluginId: String): String? {
        val plugin = synchronized(catalog.mutationLock) { factories[pluginId] }
        return plugin?.configurationError() ?: if (plugin == null) "PLUGIN_HOST_COMPONENT_UNAVAILABLE" else null
    }

    fun contents(pluginId: String): PluginContents =
        synchronized(catalog.mutationLock) {
            val plugin = factories[pluginId]
            PluginContents(
                plugin?.tools().orEmpty().map { it.descriptor.name.value to it.descriptor.description },
                plugin?.skills.orEmpty(),
            )
        }

    /** Nonblocking presentation hint only; execution still resolves the authoritative binding. */
    fun hasPublishedTools(pluginId: String): Boolean =
        toolRegistry.snapshot().any { it.ref.owner == bindingOwner("plugin", pluginId) }

    /** Immutable content facts; management sees installed content, execution sees selected owners. */
    fun skillDocuments(sessionId: String?): List<SkillDocument> =
        synchronized(catalog.mutationLock) {
            val owners = if (sessionId == null) factories.values.toList() else selectedRuntimes(sessionId)
            owners.flatMap { it.skills }.distinctBy { it.catalogEntry }
        }

    fun skillAvailable(
        key: com.helix.extensions.skills.SkillKey,
        sessionId: String?,
    ): Boolean =
        synchronized(catalog.mutationLock) {
            val owners = factories.filterValues { plugin -> plugin.skills.any { matches(it, key) } }
            owners.isEmpty() ||
                owners.any { (id, _) ->
                    ready(id) && (sessionId == null || catalog.selectionId(sessionId, id) != null)
                }
        }

    fun ownsSkill(key: com.helix.extensions.skills.SkillKey): Boolean =
        synchronized(catalog.mutationLock) { factories.values.any { plugin -> plugin.skills.any { matches(it, key) } } }

    private fun matches(
        document: SkillDocument,
        key: com.helix.extensions.skills.SkillKey,
    ): Boolean =
        document.catalogEntry.let { entry ->
            entry.source == key.source && entry.name == key.name && entry.contentHash == key.snapshotHash
        }

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

    fun configurationSaved(pluginId: String): Boolean = factories[pluginId]?.configurationSaved() == true

    fun runtimeTarget(pluginId: String): String? = factories[pluginId]?.runtimeTarget()

    private fun projectionMatches(
        state: NativePluginState,
        factory: HelixPlugin,
    ): Boolean {
        val expected = factory.tools().map { it.descriptor }.toSet()
        val published = toolRegistry.snapshot().filter { it.ref.owner == bindingOwner("plugin", state.pluginId) }
        return factory.manifest.version == state.version && factory.manifest.helixRuntimeId == state.runtimeId &&
            published.map { it.descriptor }.toSet() == expected &&
            state.fingerprint == contentFingerprint(factory, factory.tools()) &&
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
        val fingerprint = contentFingerprint(plugin, bindings)
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

    private fun contentFingerprint(
        plugin: HelixPlugin,
        bindings: List<ToolBinding>,
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                (
                    listOf(plugin.manifest.name, plugin.manifest.version, plugin.manifest.helixRuntimeId) +
                        bindings.map { it.descriptor.contractHash.hex }.sorted() +
                        plugin.skills
                            .sortedBy {
                                it.catalogEntry.name
                            }.map { "${it.catalogEntry}:${it.rawContent}" }
                ).joinToString("\n")
                    .toByteArray(Charsets.UTF_8),
            ).joinToString("") { "%02x".format(it) }

    fun all(): List<PluginManifest> =
        synchronized(catalog.mutationLock) {
            factories.filterKeys { catalog.find(it) != null }.values.map { it.manifest }
        }

    fun find(pluginId: String): PluginManifest? =
        synchronized(catalog.mutationLock) {
            factories[pluginId]?.manifest?.takeIf { catalog.find(pluginId) != null }
        }
}
