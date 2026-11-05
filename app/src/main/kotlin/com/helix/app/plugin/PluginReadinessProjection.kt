package com.helix.app.plugin

import com.helix.app.mcp.McpAppService
import com.helix.core.model.SecretAlias
import com.helix.core.storage.HelixStorage
import com.helix.extensions.plugin.PluginComponentKind
import com.helix.extensions.plugin.PluginComponentState
import com.helix.extensions.plugin.PluginRegistry
import com.helix.extensions.skills.SkillKey
import com.helix.extensions.skills.SkillListItem

/** Saved, loaded and request facts stay distinct; display names never replace the original owner identity. */
internal class PluginReadinessProjection(
    private val storage: HelixStorage,
    private val mcp: McpAppService,
    private val nativeRegistry: PluginRegistry?,
    private val browserHostAvailable: () -> Boolean,
) {
    fun snapshot(
        record: InstalledPlugin,
        items: Map<SkillKey, SkillListItem>,
        readable: Set<SkillKey>?,
    ) = PluginReadinessSnapshot(
        record.id,
        record.revision,
        false,
        record.enabled,
        record.endpoints.map(::endpointFact) + record.skills.map { skillFact(it, items[it], readable) } +
            listOfNotNull(record.native?.let { nativeFact(record, it) }),
        record.componentDiagnostics,
    )

    private fun skillFact(
        key: SkillKey,
        item: SkillListItem?,
        readable: Set<SkillKey>?,
    ): PluginComponentFact {
        val state =
            when {
                item == null -> PluginComponentState.HOST_UNAVAILABLE
                item.invocation.unsupportedExecutionFields.isNotEmpty() -> PluginComponentState.UNSUPPORTED
                !item.enabled -> PluginComponentState.DISABLED
                else -> PluginComponentState.AVAILABLE
            }
        return PluginComponentFact(
            "${key.source}:${key.name}:${key.snapshotHash}",
            PluginComponentKind.SKILL,
            state,
            item != null,
            item != null,
            setup =
                when (state) {
                    PluginComponentState.UNSUPPORTED -> PluginSetupReason.UNSUPPORTED
                    PluginComponentState.HOST_UNAVAILABLE -> PluginSetupReason.HOST_UNAVAILABLE
                    PluginComponentState.DISABLED -> PluginSetupReason.CONTENT
                    else -> null
                },
            requestAvailable = readable?.contains(key) ?: (item?.invocation?.modelInvocable == true),
            displayName = key.name,
        )
    }

    private fun endpointFact(endpoint: InstalledEndpoint): PluginComponentFact {
        val saved = storage.mcpServers.list().singleOrNull { it.id == endpoint.id }
        val loaded = saved != null && mcp.configurationLoaded(endpoint.id)
        val credential = saved?.authAlias?.let { storage.secrets.contains(SecretAlias(it)) } == true
        val state =
            when {
                endpoint.endpoint.needsCredential && !credential -> PluginComponentState.NEEDS_CONFIGURATION
                loaded -> PluginComponentState.AVAILABLE
                mcp.isActive(endpoint.id) -> PluginComponentState.RELOAD_REQUIRED
                else -> PluginComponentState.NOT_CONNECTED
            }
        val uri = java.net.URI(endpoint.endpoint.url)
        return PluginComponentFact(
            endpoint.id,
            PluginComponentKind.MCP,
            state,
            saved != null,
            loaded,
            "${uri.scheme}://${uri.rawAuthority}",
            saved?.authAlias,
            if (loaded) null else PluginSetupReason.CONNECTION,
            displayName = endpoint.endpoint.name,
        )
    }

    private fun nativeFact(
        record: InstalledPlugin,
        native: NativePluginComponent,
    ): PluginComponentFact {
        val reason =
            pluginSetupReason(
                nativeRegistry?.selectionError(native.pluginId)
                    ?: if (nativeRegistry == null) "PLUGIN_HOST_COMPONENT_UNAVAILABLE" else null,
            )
        val loaded = nativeRegistry?.ready(native.pluginId) == true
        val setup =
            reason ?: when {
                !loaded -> PluginSetupReason.RELOAD
                native.pluginId == "browser" && !browserHostAvailable() -> PluginSetupReason.HOST_UNAVAILABLE
                else -> null
            }
        val state =
            when (setup) {
                PluginSetupReason.MOBILE_CONFIGURATION -> {
                    PluginComponentState.NEEDS_CONFIGURATION
                }

                PluginSetupReason.MOBILE_BACKEND -> {
                    PluginComponentState.NOT_CONNECTED
                }

                PluginSetupReason.HOST_UNAVAILABLE -> {
                    PluginComponentState.HOST_UNAVAILABLE
                }

                null -> {
                    PluginComponentState.AVAILABLE
                }

                else -> {
                    PluginComponentState.RELOAD_REQUIRED
                }
            }
        return PluginComponentFact(
            record.id,
            if (native.pluginId == "browser") PluginComponentKind.BROWSER else PluginComponentKind.NATIVE,
            state,
            nativeRegistry?.configurationSaved(native.pluginId) == true,
            loaded,
            nativeRegistry?.runtimeTarget(native.pluginId),
            setup = setup,
        )
    }
}
