package com.helix.app.plugin

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Bounded request facts, never authorization. Full component diagnostics remain available in settings. */
internal fun pluginRequestReadiness(
    snapshots: List<PluginReadinessSnapshot>,
    installedCount: Int,
    browserAvailable: Boolean,
    selectedCount: Int = snapshots.count { it.selected },
): String {
    val entries = mutableListOf<JsonObject>()
    var omittedSelected = selectedCount - snapshots.count { it.selected }
    var omitted = installedCount - snapshots.size

    fun envelope() =
        buildJsonObject {
            put("plugins", JsonArray(entries))
            put(
                "browserHost",
                buildJsonObject {
                    put("kind", "BROWSER")
                    put("state", if (browserAvailable) "AVAILABLE" else "HOST_UNAVAILABLE")
                    put("loaded", browserAvailable)
                },
            )
            put("truncated", omitted > 0)
            put("omittedPlugins", omitted)
            put("omittedSelectedPlugins", omittedSelected)
            put("diagnostics", "DETAILS_IN_PLUGIN_SETTINGS")
        }.toString()

    snapshots
        .sortedWith(compareByDescending<PluginReadinessSnapshot> { it.selected }.thenBy { it.pluginId })
        .forEach { snapshot ->
            entries.add(snapshot.requestSummary())
            // Reserve enough bytes for final omission counters, even with large Unicode metadata.
            if (envelope().toByteArray(Charsets.UTF_8).size > MAX_PLUGIN_READINESS_BYTES - COUNTER_RESERVE_BYTES) {
                entries.removeAt(entries.lastIndex)
                omitted++
                if (snapshot.selected) omittedSelected++
            }
        }
    return envelope()
}

private fun PluginReadinessSnapshot.requestSummary() =
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
