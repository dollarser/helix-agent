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
@Suppress("TooManyFunctions") // Plugin contracts include read-only saved/backend facts alongside execution hooks.
class MobileUsePlugin(
    context: Context,
    images: com.helix.tools.framework.ToolImagePublication,
    val permissionCenter: AutomationPermissionCenter,
    private val taskHost: com.helix.extensions.plugin.PluginTaskHost? = null,
    private val overlayContext: (() -> Context?)? = null,
    private val enabled: () -> Boolean = { true },
) : HelixPlugin,
    com.helix.extensions.plugin.PluginApprovalPresentation {
    override val manifest: PluginManifest =
        context.assets.open(MANIFEST_ASSET).use { PluginManifestReader.parse(it.readBytes()) }

    override val skills =
        listOf(
            com.helix.extensions.skills.SkillLoader().loadBuiltIn(
                context.assets
                    .open("plugins/mobile-use/skills/android-ui-task/SKILL.md")
                    .bufferedReader()
                    .use { it.readText() },
                "android-ui-task",
                mapOf(
                    "references/android-ui-reference.md" to
                        context.assets
                            .open("plugins/mobile-use/skills/android-ui-task/references/android-ui-reference.md")
                            .bufferedReader()
                            .use { it.readText() },
                ),
            ),
        )

    override val requiredInstructions =
        context.assets
            .open("plugins/mobile-use/required.md")
            .bufferedReader()
            .use { it.readText() }

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

    private val appContext = context.applicationContext

    @Volatile private var presentationResource:
        com.helix.extensions.plugin.NativeResource<
            com.helix.extensions.mobileuse.automation.AutomationRuntimePresentation,
        >? = null

    init {
        require(manifest.name == PLUGIN_ID) { "unexpected Mobile Use plugin id: ${manifest.name}" }
        require(manifest.helixRuntimeId == RUNTIME_ID) { "unexpected Mobile Use runtime binding" }
    }

    @Suppress("TooGenericExceptionCaught") // Clean this initialization and rethrow the original failure.
    override fun openRegistration(): AutoCloseable? {
        val host = taskHost ?: return null
        check(presentationResource?.state in setOf(null, com.helix.extensions.plugin.NativeResource.State.CLOSED)) {
            "MOBILE_USE_PRESENTATION_NOT_RELEASED"
        }
        val shared =
            MobileUseOverlay(appContext, host, surface = {
                overlayContext?.invoke()
                    ?: if (overlayContext == null) permissionCenter.accessibilityOverlayContext() else null
            }, enabled = enabled)
        val resource =
            com.helix.extensions.plugin.NativeResource<
                com.helix.extensions.mobileuse.automation.AutomationRuntimePresentation,
            >(shared) { it.close() }
        try {
            val registration =
                com.helix.extensions.mobileuse.automation.AutomationRuntimePresentationFactory
                    .register(resource)
            presentationResource = resource
            return registration
        } catch (error: Throwable) {
            try {
                resource.close()
            } catch (cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    override fun registrationError(): String? =
        if (taskHost == null || presentationResource?.state ==
            com.helix.extensions.plugin.NativeResource.State.ACTIVE
        ) {
            null
        } else {
            "MOBILE_USE_PRESENTATION_${presentationResource?.state ?: "UNAVAILABLE"}"
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
            "ui.screenshot",
            "ui.gesture",
            "android.open_uri",
        )

    override fun configurationError(): String? =
        MobileUseSelectionReadiness.error(permissionCenter.globalConfiguration() != null) {
            permissionCenter.preferredBackend(
                com.helix.extensions.mobileuse.automation.AutomationDeviceOperation.SNAPSHOT,
            ) != null
        }

    override fun configurationSaved(): Boolean = permissionCenter.globalConfiguration() != null

    override fun runtimeTarget(): String? =
        permissionCenter
            .preferredBackend(
                com.helix.extensions.mobileuse.automation.AutomationDeviceOperation.SNAPSHOT,
            )?.name

    override fun scopeFor(
        descriptor: com.helix.tools.framework.ToolDescriptor,
        sessionId: String,
    ): UserScope? = dispatchScopeFor(descriptor, sessionId)

    override fun imageScope(sessionId: String): UserScope? =
        permissionCenter.conversationGrant(sessionId)?.takeIf { it.shareScreens }?.scope

    override fun tools(): List<ToolBinding> =
        automation.descriptors().map { descriptor ->
            ToolBinding(descriptor, present(descriptor.name.value, automation.executor(descriptor.name.value)))
        } +
            device.descriptors().map { descriptor ->
                ToolBinding(descriptor, present(descriptor.name.value, device.executor(descriptor.name.value)))
            }

    private fun present(
        toolName: String,
        executor: com.helix.tools.framework.ToolExecutor,
    ): com.helix.tools.framework.ToolExecutor =
        if (taskHost == null) {
            executor
        } else {
            MobileUsePresentedExecutor(
                executor,
                { presentationResource?.borrow() },
                retainsMobileUseOverlay(toolName),
            ) { call ->
                permissionCenter.admissionError(call)
            }
        }

    private val contracts by lazy { MobileUseToolContracts(tools().map { it.descriptor }) }

    override fun showApproval(task: com.helix.extensions.plugin.PluginTaskIdentity) {
        presentationResource?.borrow()?.use { lease ->
            (lease.value as MobileUseOverlay).showApproval(task)
        }
    }

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
