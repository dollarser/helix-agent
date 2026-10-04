package com.helix.app.automation

import android.content.Context
import androidx.compose.runtime.Composable
import com.helix.app.automation.shizuku.ShizukuAutomationBackend
import com.helix.core.policy.MobileUseGrantStore
import com.helix.core.policy.UserScope
import com.helix.extensions.mobileuse.MobileUsePlugin
import com.helix.extensions.plugin.PluginRegistry

/** Flavor-local adapter for plugin configuration and device grants; conversation selection lives in the catalog. */
internal object AutomationModule {
    private var appContext: Context? = null
    private var runtime: MobileUsePlugin? = null
    private var settingsStore: MobileUseGrantStore? = null

    @Synchronized
    fun register(
        context: Context,
        plugins: PluginRegistry,
        images: com.helix.tools.framework.ToolImagePublication,
        grants: MobileUseGrantStore,
        conversationExists: (String) -> Boolean,
        taskHost: com.helix.extensions.plugin.PluginTaskHost? = null,
    ) {
        com.helix.app.automation.shizuku.MobileUseRootConnection
            .configure(context)
        val plugin =
            runtime
                ?: MobileUsePlugin(
                    context.applicationContext,
                    images,
                    taskHost,
                    ShizukuAutomationBackend(context.applicationContext),
                    com.helix.app.automation.shizuku.RootAutomationBackend(
                        com.helix.app.automation.shizuku.MobileUseRootConnection::access,
                    ),
                ) {
                    plugins.hasPublishedTools(MobileUsePlugin.PLUGIN_ID)
                }.also { runtime = it }
        if (plugins.find(MobileUsePlugin.PLUGIN_ID) == null) plugins.register(plugin)
        plugin.permissionCenter.configureConversations(grants, conversationExists)
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

    fun owns(descriptor: com.helix.tools.framework.ToolDescriptor?): Boolean = runtime?.owns(descriptor) == true

    fun dispatchScopeFor(
        descriptor: com.helix.tools.framework.ToolDescriptor?,
        conversationId: String?,
    ): UserScope? = runtime?.dispatchScopeFor(descriptor, conversationId)

    fun skillContext(): String? = runtime?.takeIf { it.isAvailable() }?.skillContext

    @Composable
    @Suppress("FunctionName")
    fun Settings(onPermissions: () -> Unit) {
        val context = appContext ?: return
        val store = settingsStore ?: return
        runtime?.permissionCenter?.let { MobileUseBackendStatus(it) }
        MobileUseSettings(context, store, onPermissions)
    }

    @Composable
    @Suppress("FunctionName")
    fun Permissions() {
        val context = appContext ?: return
        val center = runtime?.permissionCenter ?: return
        MobileUsePermissions(context, center)
    }
}
