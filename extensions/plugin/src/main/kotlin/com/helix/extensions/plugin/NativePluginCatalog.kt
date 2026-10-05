package com.helix.extensions.plugin

/** Projection of the one durable installation record; never another installation store. */
data class NativePluginState(
    val pluginId: String,
    val runtimeId: String,
    val version: String,
    val fingerprint: String,
    val enabled: Boolean,
    val revision: Long,
)

/** Implemented by the host's ordinary package catalog, not by the runtime factory registry. */
interface NativePluginCatalog {
    val mutationLock: Any

    fun selectionId(
        sessionId: String,
        pluginId: String,
    ): String? = null

    fun find(pluginId: String): NativePluginState?

    fun publish(
        state: NativePluginState,
        expectedRevision: Long?,
    )
}
