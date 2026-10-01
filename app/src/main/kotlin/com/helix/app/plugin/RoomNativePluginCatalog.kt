package com.helix.app.plugin

import com.helix.extensions.plugin.NativePluginCatalog
import com.helix.extensions.plugin.NativePluginState

/** The same installation manifest, version and session selection used by imported packages. */
class RoomNativePluginCatalog(
    private val catalog: PluginCatalog,
) : NativePluginCatalog {
    override val mutationLock get() = catalog.mutationLock

    override fun find(pluginId: String): NativePluginState? =
        catalog.list().singleOrNull { it.native?.pluginId == pluginId }?.nativeState()

    override fun publish(
        state: NativePluginState,
        expectedRevision: Long?,
    ) {
        val existing = catalog.list().singleOrNull { it.native?.pluginId == state.pluginId }
        require(existing?.revision == expectedRevision) { "PLUGIN_VERSION_CHANGED" }
        require(state.revision == (expectedRevision ?: 0) + 1) { "PLUGIN_REVISION_INVALID" }
        val record =
            existing?.copy(
                hash = state.fingerprint,
                versionLabel = state.version,
                enabled = state.enabled,
                revision = state.revision,
                native = NativePluginComponent(state.pluginId, state.runtimeId),
            ) ?: InstalledPlugin(
                id = "bundled-plugin:${state.pluginId}",
                name = state.pluginId,
                source = "BUNDLED_PLUGIN",
                hash = state.fingerprint,
                endpoints = emptyList(),
                skills = emptyList(),
                diagnostics = emptyList(),
                identity = "bundled-plugin:${state.pluginId}",
                revision = state.revision,
                sessionScoped = true,
                versionLabel = state.version,
                enabled = state.enabled,
                native = NativePluginComponent(state.pluginId, state.runtimeId),
            )
        catalog.publishNative(record, expectedRevision)
    }

    private fun InstalledPlugin.nativeState(): NativePluginState {
        val component = requireNotNull(native)
        return NativePluginState(
            component.pluginId,
            component.runtimeId,
            requireNotNull(versionLabel),
            hash,
            enabled,
            revision,
        )
    }
}
