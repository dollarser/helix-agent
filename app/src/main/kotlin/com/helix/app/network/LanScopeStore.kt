package com.helix.app.network

import com.helix.app.internal.LineStore
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.policy.NetworkOriginScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-created host/port capability scopes. They never create a Tool Approval Proof. */
class LanScopeStore(
    private val storage: LineStore,
    private val advancedEnabled: () -> Boolean,
) {
    private val state = MutableStateFlow(load())
    val origins: StateFlow<List<String>> = state.asStateFlow()

    @Synchronized
    fun current(): Set<NetworkOriginScope> =
        state.value
            .map { origin ->
                val endpoint = NormalizedEndpoint.parse(origin)
                NetworkOriginScope(endpoint.host, endpoint.port)
            }.toSet()

    @Synchronized
    fun add(origin: String) {
        check(advancedEnabled()) { "Advanced must be explicitly enabled" }
        val next = (state.value + normalize(origin)).distinct().sorted()
        require(next.size <= MAX_ORIGINS) { "Too many LAN origins" }
        storage.setLines(KEY, next)
        state.value = next
    }

    @Synchronized
    fun remove(origin: String) {
        val next = state.value - normalize(origin)
        // Revoke in this process even if durable storage fails; the caller sees that failure.
        state.value = next
        storage.setLines(KEY, next)
    }

    private fun load(): List<String> =
        try {
            val lines = storage.lines(KEY)
            require(lines.size <= MAX_ORIGINS)
            lines.map(::normalize).distinct().sorted()
        } catch (_: IllegalArgumentException) {
            emptyList() // malformed persisted capability data never grants access
        }

    private fun normalize(raw: String): String {
        val endpoint = NormalizedEndpoint.parse(raw.trim())
        require(endpoint.path.isEmpty() || endpoint.path == "/") { "Enter only scheme, host and port" }
        return endpoint.origin
    }

    private companion object {
        const val KEY = "lan_origins_v1"
        const val MAX_ORIGINS = 64
    }
}
