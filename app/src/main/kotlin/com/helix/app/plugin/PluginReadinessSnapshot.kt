package com.helix.app.plugin

import com.helix.extensions.plugin.PluginComponentKind
import com.helix.extensions.plugin.PluginComponentState
import com.helix.extensions.plugin.PluginDiagnostic
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

enum class PluginSetupReason {
    MOBILE_SCOPE,
    MOBILE_BACKEND,
    HOST_UNAVAILABLE,
    CONNECTION,
    CONTENT,
    UNSUPPORTED,
    RELOAD,
}

class PluginSelectionUnavailable(
    val reason: PluginSetupReason,
) : IllegalStateException(reason.name)

/** Only application/adapter code interprets engine failure codes. UI consumes the typed result. */
internal fun pluginSetupReason(code: String?): PluginSetupReason? =
    when (code) {
        null -> null
        "MOBILE_USE_NOT_CONFIGURED" -> PluginSetupReason.MOBILE_SCOPE
        "MOBILE_USE_BACKEND_UNAVAILABLE" -> PluginSetupReason.MOBILE_BACKEND
        "PLUGIN_HOST_COMPONENT_UNAVAILABLE" -> PluginSetupReason.HOST_UNAVAILABLE
        else -> PluginSetupReason.RELOAD
    }

data class PluginComponentFact(
    val ownerId: String,
    val kind: PluginComponentKind,
    val state: PluginComponentState,
    val saved: Boolean,
    val loaded: Boolean,
    val target: String? = null,
    val accountReference: String? = null,
    val setup: PluginSetupReason? = null,
    val requestAvailable: Boolean = true,
) {
    val ready: Boolean get() = state == PluginComponentState.AVAILABLE

    fun requestState(
        selected: Boolean,
        enabled: Boolean,
    ): PluginComponentState =
        when {
            !enabled -> PluginComponentState.DISABLED
            !selected -> PluginComponentState.NOT_SELECTED
            !requestAvailable && ready -> PluginComponentState.REQUEST_UNAVAILABLE
            else -> state
        }
}

data class PluginReadinessSnapshot(
    val pluginId: String,
    val revision: Long,
    val selected: Boolean,
    val enabled: Boolean,
    val components: List<PluginComponentFact>,
    val diagnostics: List<PluginDiagnostic>,
) {
    val ready: Int get() = if (enabled) components.count { it.ready } else 0
    val fullyReady: Boolean get() = components.isNotEmpty() && ready == components.size && !hasSkippedComponents
    val hasSkippedComponents: Boolean get() = diagnostics.any { it.omitted }
    val setup: PluginSetupReason? get() = if (ready > 0) null else components.firstNotNullOfOrNull { it.setup }

    fun modelFacts() =
        buildJsonObject {
            put("pluginId", pluginId)
            put("revision", revision)
            put("selected", selected)
            put("effect", "NEXT_REQUEST")
            put(
                "components",
                JsonArray(
                    components.map { component ->
                        buildJsonObject {
                            put("ownerId", component.ownerId)
                            put("kind", component.kind.name)
                            put("state", component.state.name)
                            put(
                                "requestState",
                                component.requestState(selected, enabled).name,
                            )
                            put("saved", component.saved)
                            put("loaded", component.loaded)
                            component.target?.let { put("target", it) }
                            component.accountReference?.let { put("accountReference", it) }
                        }
                    },
                ),
            )
            put("diagnostics", JsonArray(diagnostics.map { it.modelFacts() }))
        }
}
