package com.helix.app.plugin

import com.helix.extensions.plugin.PluginEndpoint

/** A named component, not merely a URL, owns its local account identity. */
internal object PluginEndpointBindings {
    fun reconcile(
        previous: List<InstalledEndpoint>,
        incoming: List<PluginEndpoint>,
        newId: () -> String,
    ): List<InstalledEndpoint> {
        require(incoming.map { it.name }.distinct().size == incoming.size) { "PLUGIN_DUPLICATE_ENDPOINT" }
        val result =
            incoming.map { endpoint ->
                val old = previous.singleOrNull { it.endpoint == endpoint }
                InstalledEndpoint(old?.id ?: newId(), endpoint)
            }
        require(result.all { it.id.isNotBlank() } && result.map { it.id }.distinct().size == result.size) {
            "PLUGIN_ENDPOINT_ID_COLLISION"
        }
        return result
    }
}
