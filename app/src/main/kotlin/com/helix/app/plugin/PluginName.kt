package com.helix.app.plugin

import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

/** Read-only presentation/discovery metadata. Neither labels nor aliases are installation identities. */
data class PluginName(
    val original: String,
    val nativePluginId: String? = null,
) {
    private val translations: Map<String, String>
        get() = nativePluginId?.let { bundledPluginNames[it] }.orEmpty()

    /** Both supported languages remain discoverable regardless of the current UI language. */
    val aliases: List<String>
        get() = (listOf(original) + listOfNotNull(nativePluginId) + translations.values).distinct()

    fun displayName(languageTags: List<String>): String =
        languageTags.firstNotNullOfOrNull { tag ->
            translations[tag.lowercase(Locale.ROOT)] ?: translations[Locale.forLanguageTag(tag).language]
        } ?: translations["en"] ?: original
}

internal fun InstalledPlugin.pluginName() = PluginName(name, native?.pluginId)

/** PluginOrigin is minted by the trusted native registry, never inferred from a tool's spelling. */
internal fun nativePluginName(origin: ToolOrigin): PluginName? =
    (origin as? ToolOrigin.PluginOrigin)?.let {
        PluginName(it.pluginId, it.pluginId.takeIf { id -> id == it.runtimeId })
    }

private val bundledPluginNames: Map<String, Map<String, String>> by lazy {
    val stream = requireNotNull(PluginName::class.java.getResourceAsStream("/plugin-names.json"))
    val document = stream.bufferedReader().use { it.readText() }
    Json.parseToJsonElement(document).jsonObject.mapValues { (_, translations) ->
        translations.jsonObject
            .mapValues { (_, value) ->
                value.jsonPrimitive.content.also { require(it.isNotBlank()) }
            }.also { require(it["en"] != null) }
    }
}
