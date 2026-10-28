package com.helix.app.plugin

/** Validate the complete picker draft before any durable write or component configuration. */
internal fun pluginSelectionChanges(
    expected: Map<String, Boolean>,
    selected: Set<String>,
    current: Set<String>,
    rows: List<PluginSessionRow>,
): Map<String, Boolean> {
    require(selected.all { it in expected }) { "PLUGIN_SELECTION_SCOPE" }
    check(expected.all { (id, value) -> (id in current) == value }) { "PLUGIN_SELECTION_CHANGED" }
    val available = rows.associateBy { it.id }
    selected.filter { expected[it] != true }.forEach { id ->
        val row = available[id] ?: error("PLUGIN_NOT_INSTALLED")
        if (!row.enabled || !row.available || row.readyComponents == 0) {
            throw PluginSelectionUnavailable(row.setupReason ?: PluginSetupReason.HOST_UNAVAILABLE)
        }
    }
    return expected.filter { (id, before) -> before != (id in selected) }.mapValues { (id, _) -> id in selected }
}
