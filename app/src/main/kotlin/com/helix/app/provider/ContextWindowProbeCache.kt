package com.helix.app.provider

/** Rate-limit optional context discovery misses without blocking requests for other exact models. */
internal class ContextWindowProbeCache {
    private val probes = java.util.concurrent.ConcurrentHashMap<List<String>, Long>()

    fun due(
        key: List<String>,
        time: Long,
    ): Boolean {
        val previous = probes.putIfAbsent(key, time)
        return previous == null || (time - previous >= 60_000 && probes.replace(key, previous, time))
    }
}
