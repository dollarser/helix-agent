package com.helix.extensions.plugin

/** Contract fixture; the Android integration tests exercise the actual shared Room catalog. */
internal class MemoryNativePluginCatalog : NativePluginCatalog {
    override val mutationLock = Any()
    val states = linkedMapOf<String, NativePluginState>()
    val selections = mutableMapOf<Pair<String, String>, String>()

    override fun selectionId(
        sessionId: String,
        pluginId: String,
    ) = selections[sessionId to pluginId]

    var failPublication = false

    override fun find(pluginId: String) = states[pluginId]

    override fun publish(
        state: NativePluginState,
        expectedRevision: Long?,
    ) {
        check(!failPublication) { "fixture publication failure" }
        check(states[state.pluginId]?.revision == expectedRevision)
        check(state.revision == (expectedRevision ?: 0) + 1)
        states[state.pluginId] = state
    }
}
