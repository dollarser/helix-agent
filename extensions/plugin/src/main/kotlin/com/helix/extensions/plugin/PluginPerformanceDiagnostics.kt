package com.helix.extensions.plugin

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class PluginMeasurement {
    REGISTRATION,
    READINESS,
    FINGERPRINT,
}

data class PluginMeasurementSummary(
    val pluginId: String,
    val operation: PluginMeasurement,
    val calls: Long,
    val failures: Long,
    val totalNanos: Long,
    val maxNanos: Long,
)

/** Process-local measurements, never readiness/authority state. No content or exception messages. */
class PluginPerformanceDiagnostics(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private val summaries = LinkedHashMap<Pair<String, PluginMeasurement>, PluginMeasurementSummary>()
    private var omittedMeasurements = 0L

    internal fun <T> measure(
        pluginId: String,
        operation: PluginMeasurement,
        action: () -> T,
    ): T {
        if (!PortablePluginManifest.validName(pluginId)) return action()
        val started = nanoTime()
        var completed = false
        try {
            return action().also { completed = true }
        } finally {
            val elapsed = (nanoTime() - started).coerceAtLeast(0)
            synchronized(lock) {
                val key = pluginId to operation
                // Trusted native IDs only; bound even accidental repeated registration attempts.
                if (key in summaries || summaries.size < MAX_SUMMARIES) {
                    val previous = summaries[key]
                    summaries[key] =
                        PluginMeasurementSummary(
                            pluginId,
                            operation,
                            (previous?.calls ?: 0) + 1,
                            (previous?.failures ?: 0) + if (completed) 0 else 1,
                            (previous?.totalNanos ?: 0) + elapsed,
                            maxOf(previous?.maxNanos ?: 0, elapsed),
                        )
                } else {
                    omittedMeasurements++
                }
            }
        }
    }

    fun snapshot(): List<PluginMeasurementSummary> = synchronized(lock) { summaries.values.toList() }

    fun json() =
        buildJsonObject {
            put("scope", "current_process")
            put("timing", "monotonic_nanoseconds_inclusive")
            put("failureDefinition", "exception_or_cancellation")
            put("omittedMeasurements", synchronized(lock) { omittedMeasurements })
            put(
                "measurements",
                buildJsonArray {
                    snapshot().forEach { row ->
                        add(
                            buildJsonObject {
                                put("pluginId", row.pluginId)
                                put("operation", row.operation.name)
                                put("calls", row.calls)
                                put("failures", row.failures)
                                put("totalNanos", row.totalNanos)
                                put("maxNanos", row.maxNanos)
                            },
                        )
                    }
                },
            )
        }

    companion object {
        private const val MAX_SUMMARIES = 32
    }
}
