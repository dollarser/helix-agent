package com.helix.extensions.mobileuse

import android.content.Context
import com.helix.core.policy.UserScope
import com.helix.extensions.mobileuse.automation.AutomationPermissionCenter
import com.helix.extensions.mobileuse.automation.PermissionCenterAutomationToolPort
import com.helix.extensions.mobileuse.tools.AutomationTools
import com.helix.extensions.plugin.HelixPlugin
import com.helix.extensions.plugin.PluginManifest
import com.helix.extensions.plugin.PluginManifestReader
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
    val permissionCenter: AutomationPermissionCenter,
    taskHost: com.helix.extensions.plugin.PluginTaskHost? = null,
    private val enabled: () -> Boolean = { true },
) : HelixPlugin {
    override val manifest: PluginManifest =
        context.assets.open(MANIFEST_ASSET).use { PluginManifestReader.parse(it.readBytes()) }

    override val bundledSkills =
        listOf(
            com.helix.extensions.plugin.PluginBundledSkill(
                "android-ui-task",
                "Operate authorized Android apps with fresh observations, bounded waits and result verification.",
                context.assets
                    .open("plugins/mobile-use/skills/android-ui-task/SKILL.md")
                    .bufferedReader()
                    .use { it.readText() },
            ),
        )

    private val origin =
        ToolOrigin.PluginOrigin(
            pluginId = manifest.name,
            pluginVersion = manifest.version,
            runtimeId = requireNotNull(manifest.helixRuntimeId) { "Mobile Use manifest has no Helix runtime binding" },
        )
    private val automation = AutomationTools(PermissionCenterAutomationToolPort(permissionCenter), origin)
    private val device =
        com.helix.extensions.mobileuse.tools.AutomationDeviceTools(
            com.helix.extensions.mobileuse.automation
                .PermissionCenterDevicePort(permissionCenter),
            PermissionCenterAutomationToolPort(permissionCenter),
            images,
            origin,
        )

    init {
        require(manifest.name == PLUGIN_ID) { "unexpected Mobile Use plugin id: ${manifest.name}" }
        require(manifest.helixRuntimeId == RUNTIME_ID) { "unexpected Mobile Use runtime binding" }
        if (taskHost != null) {
            com.helix.extensions.mobileuse.automation.AutomationRuntimePresentationFactory.create = { service ->
                MobileUseOverlay(service, taskHost, enabled)
            }
        }
    }

    override val dataOrigin = com.helix.core.policy.DataOrigin.ACCESSIBILITY
    override val preferredTools =
        setOf(
            "ui.apps",
            "ui.launch",
            "ui.snapshot",
            "ui.find",
            "ui.click",
            "ui.click_match",
            "ui.set_text",
            "ui.scroll",
            "ui.back",
            "ui.wait",
            "ui.device",
            "ui.screenshot",
            "ui.gesture",
            "android.open_uri",
        )

    override fun configurationError(): String? =
        if (permissionCenter.globalConfiguration() == null) "MOBILE_USE_NOT_CONFIGURED" else null

    override fun scopeFor(
        descriptor: com.helix.tools.framework.ToolDescriptor,
        sessionId: String,
    ): UserScope? = dispatchScopeFor(descriptor, sessionId)

    override fun imageScope(sessionId: String): UserScope? =
        permissionCenter.conversationGrant(sessionId)?.takeIf { it.shareScreens }?.scope

    override fun tools(): List<ToolBinding> =
        automation.descriptors().map { descriptor ->
            ToolBinding(descriptor, automation.executor(descriptor.name.value))
        } +
            device.descriptors().map { descriptor ->
                ToolBinding(descriptor, device.executor(descriptor.name.value))
            }

    private val contracts by lazy { MobileUseToolContracts(tools().map { it.descriptor }) }

    fun isAvailable(): Boolean = enabled()

    fun owns(descriptor: com.helix.tools.framework.ToolDescriptor?): Boolean = enabled() && contracts.owns(descriptor)

    fun dispatchScopeFor(
        descriptor: com.helix.tools.framework.ToolDescriptor?,
        conversationId: String?,
    ): UserScope? = if (owns(descriptor)) scopeFor(descriptor?.name?.value, conversationId) else null

    fun scopeFor(
        toolName: String?,
        conversationId: String?,
    ): UserScope? =
        if (enabled() && contracts.containsName(toolName) && conversationId != null) {
            permissionCenter
                .availableConversationGrant(
                    conversationId,
                    com.helix.extensions.mobileuse.tools.AutomationEnablement
                        .capabilityFor(toolName),
                )?.scope
        } else {
            null
        }

    companion object {
        const val PLUGIN_ID = "mobile-use"
        const val RUNTIME_ID = "mobile-use"
        const val MANIFEST_ASSET = "plugins/mobile-use/plugin.json"
    }
}
