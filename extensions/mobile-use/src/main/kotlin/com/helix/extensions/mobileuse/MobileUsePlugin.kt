package com.helix.extensions.mobileuse

import android.content.Context
import com.helix.core.policy.UserScope
import com.helix.extensions.plugin.HelixPlugin
import com.helix.extensions.plugin.PluginManifest
import com.helix.extensions.plugin.PluginManifestReader
import com.helix.tools.automation.AutomationPermissionCenter
import com.helix.tools.automation.AutomationTools
import com.helix.tools.automation.PermissionCenterAutomationToolPort
import com.helix.tools.framework.ToolBinding
import com.helix.tools.framework.ToolOrigin

/**
 * The first Helix host-native Agent Plugin runtime. It owns no planning/turn state: the existing
 * Automation permission center supplies Android facts/actions and every call still executes via
 * the ordinary Helix ToolDispatcher.
 */
class MobileUsePlugin(
    context: Context,
    images: com.helix.tools.framework.ToolImagePublication,
) : HelixPlugin {
    override val manifest: PluginManifest =
        context.assets.open(MANIFEST_ASSET).use { PluginManifestReader.parse(it.readBytes()) }

    val permissionCenter = AutomationPermissionCenter(context.applicationContext)

    private val origin =
        ToolOrigin.PluginOrigin(
            pluginId = manifest.name,
            pluginVersion = manifest.version,
            runtimeId = requireNotNull(manifest.helixRuntimeId) { "Mobile Use manifest has no Helix runtime binding" },
        )
    private val automation = AutomationTools(PermissionCenterAutomationToolPort(permissionCenter), origin)
    private val device =
        com.helix.tools.automation.AutomationDeviceTools(
            com.helix.tools.automation
                .PermissionCenterDevicePort(permissionCenter),
            PermissionCenterAutomationToolPort(permissionCenter),
            images,
            origin,
        )

    init {
        require(manifest.name == PLUGIN_ID) { "unexpected Mobile Use plugin id: ${manifest.name}" }
        require(manifest.helixRuntimeId == RUNTIME_ID) { "unexpected Mobile Use runtime binding" }
    }

    override fun tools(): List<ToolBinding> =
        automation.descriptors().map { descriptor ->
            ToolBinding(descriptor, automation.executor(descriptor.name.value))
        } +
            device.descriptors().map { descriptor ->
                ToolBinding(descriptor, device.executor(descriptor.name.value))
            }

    fun scopeFor(
        toolName: String?,
        conversationId: String?,
    ): UserScope? =
        if (toolName?.startsWith("ui.") == true && conversationId != null) {
            permissionCenter.conversationGrant(conversationId)?.scope
        } else {
            null
        }

    companion object {
        const val PLUGIN_ID = "mobile-use"
        const val RUNTIME_ID = "mobile-use"
        const val MANIFEST_ASSET = "plugins/mobile-use/plugin.json"
    }
}
