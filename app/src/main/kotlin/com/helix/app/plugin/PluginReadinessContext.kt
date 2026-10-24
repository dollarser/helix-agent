package com.helix.app.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bounded acquisition snapshot. Captured counts are not a claim that every row fits a model request. */
data class PluginReadinessContext(
    val plugins: List<PluginReadinessSnapshot>,
    val installedCount: Int,
    val browserAvailable: Boolean,
    val omittedPlugins: Int,
    val omittedSelectedPlugins: Int,
) {
    fun acquisitionSummary(): JsonObject =
        buildJsonObject {
            put("installedPlugins", installedCount)
            put("capturedPlugins", plugins.size)
            put("acquisitionTruncated", omittedPlugins > 0)
            put("omittedAtAcquisition", omittedPlugins)
            put("omittedSelectedAtAcquisition", omittedSelectedPlugins)
            put("browserHost", if (browserAvailable) "AVAILABLE" else "HOST_UNAVAILABLE")
            put("projection", "Individual optional rows may be omitted by the model context budget")
            put("diagnostics", "DETAILS_IN_PLUGIN_SETTINGS")
        }
}

/** Snapshot acquisition remains bounded independently of the common model-window projection. */
internal fun pluginReadinessContext(
    snapshots: List<PluginReadinessSnapshot>,
    installedCount: Int,
    browserAvailable: Boolean,
    selectedCount: Int = snapshots.count { it.selected },
): PluginReadinessContext {
    require(installedCount >= snapshots.size && selectedCount in 0..installedCount)
    val included = mutableListOf<PluginReadinessSnapshot>()
    var bytes = 256L
    var omittedSelected = selectedCount - snapshots.count { it.selected }
    snapshots
        .sortedWith(compareByDescending<PluginReadinessSnapshot> { it.selected }.thenBy { it.pluginId })
        .forEach { snapshot ->
            val size =
                snapshot
                    .requestSummary()
                    .toString()
                    .toByteArray(Charsets.UTF_8)
                    .size
                    .toLong() + 1
            if (size <= MAX_PLUGIN_READINESS_BYTES - COUNTER_RESERVE_BYTES - bytes) {
                included += snapshot
                bytes += size
            } else if (snapshot.selected) {
                omittedSelected++
            }
        }
    return PluginReadinessContext(
        included.toList(),
        installedCount,
        browserAvailable,
        installedCount - included.size,
        omittedSelected,
    )
}

internal fun PluginReadinessSnapshot.requestSummary() =
    buildJsonObject {
        put("pluginId", pluginId)
        put("revision", revision)
        put("selected", selected)
        put("enabled", enabled)
        put("effect", "NEXT_REQUEST")
        put(
            "components",
            JsonArray(
                components
                    .groupBy {
                        listOf(
                            it.kind.name,
                            it.state.name,
                            it.requestState(selected, enabled).name,
                            it.saved.toString(),
                            it.loaded.toString(),
                        )
                    }.map { (facts, group) ->
                        buildJsonObject {
                            put("kind", facts[0])
                            put("state", facts[1])
                            put("requestState", facts[2])
                            put("saved", group.first().saved)
                            put("loaded", group.first().loaded)
                            put("count", group.size)
                        }
                    },
            ),
        )
        put("diagnosticCount", diagnostics.size)
        put("omittedComponentCount", diagnostics.count { it.omitted })
    }

internal const val MAX_PLUGIN_READINESS_BYTES = 8192
private const val COUNTER_RESERVE_BYTES = 64
