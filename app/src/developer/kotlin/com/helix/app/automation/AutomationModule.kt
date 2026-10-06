package com.helix.app.automation

import android.content.Context
import androidx.compose.runtime.Composable
import com.helix.core.policy.UserScope
import com.helix.extensions.mobileuse.MobileUsePlugin
import com.helix.extensions.mobileuse.automation.backend.ShizukuAutomationBackend
import com.helix.extensions.mobileuse.config.MobileUseGrantStore
import com.helix.extensions.plugin.PluginRegistry

/** Flavor-local adapter for plugin configuration and device grants; conversation selection lives in the catalog. */
internal object AutomationModule {
    private var appContext: Context? = null
    private var runtime: MobileUsePlugin? = null
    private var settingsStore: MobileUseGrantStore? = null

    @Synchronized
    @Suppress("LongParameterList") // composition root injects storage, selection, images and task services
    fun register(
        context: Context,
        plugins: PluginRegistry,
        images: com.helix.tools.framework.ToolImagePublication,
        readConfiguration: (String) -> List<String>,
        writeConfiguration: (String, List<String>) -> Unit,
        selectionId: (String) -> String?,
        conversationExists: (String) -> Boolean,
        taskHost: com.helix.extensions.plugin.PluginTaskHost? = null,
    ) {
        com.helix.tools.deviceaccess.DeviceAccess
            .configure(
                context,
                "mobile-use",
                com.helix.extensions.mobileuse.automation.backend.RootAutomationService::class.java,
                enabled = { plugins.hasPublishedTools(MobileUsePlugin.PLUGIN_ID) },
            )
        val grants = MobileUseGrantStore(readConfiguration, writeConfiguration, selectionId)
        val plugin =
            runtime
                ?: MobileUsePlugin(
                    context.applicationContext,
                    images,
                    com.helix.extensions.mobileuse.automation.AutomationPermissionCenter(
                        context.applicationContext,
                        ShizukuAutomationBackend(context.applicationContext),
                        com.helix.extensions.mobileuse.automation.backend.RootAutomationBackend(
                            {
                                com.helix.tools.deviceaccess.DeviceAccess
                                    .root("mobile-use")
                            },
                        ),
                    ),
                    taskHost,
                    overlayContext = {
                        if (android.provider.Settings.canDrawOverlays(context)) {
                            context.applicationContext
                        } else {
                            runtime?.permissionCenter?.accessibilityOverlayContext()
                        }
                    },
                ) {
                    plugins.hasPublishedTools(MobileUsePlugin.PLUGIN_ID)
                }.also { runtime = it }
        if (plugins.find(MobileUsePlugin.PLUGIN_ID) == null) plugins.register(plugin)
        plugin.permissionCenter.configureConversations(grants, conversationExists, plugin::isAvailable)
        appContext = context.applicationContext
        settingsStore = grants
    }

    fun capabilityState(): com.helix.core.policy.GrantState =
        if (runtime?.isAvailable() == true) {
            com.helix.core.policy.GrantState.GRANTED
        } else {
            com.helix.core.policy.GrantState.UNAVAILABLE
        }

    fun scopeFor(
        toolName: String?,
        conversationId: String?,
    ): UserScope? = runtime?.scopeFor(toolName, conversationId)

    @Composable
    @Suppress("FunctionName")
    fun Settings(onPermissions: () -> Unit) {
        val context = appContext ?: return
        val store = settingsStore ?: return
        runtime?.permissionCenter?.let { MobileUseBackendStatus(it) }
        MobileUseRootConnection()
        MobileUseSettings(context, store, onPermissions)
    }
}
