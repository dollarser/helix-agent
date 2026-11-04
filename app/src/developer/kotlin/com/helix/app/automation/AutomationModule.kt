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
    fun isConfigurationResetRequired(error: Exception): Boolean =
        error is com.helix.extensions.mobileuse.config.MobileUseConfigurationUnreadable

    fun previewConfiguration(context: Context): com.helix.app.maintenance.PreviewConfigurationAccess {
        val lines =
            com.helix.app.internal
                .PrefsLineStore(context, "helix-mobile-use", synchronous = true)
        val store = MobileUseGrantStore(lines::lines, { _, _ -> error("Read-only inspection") }, { null })
        return com.helix.app.maintenance.PreviewConfigurationAccess(
            { lines.lines(MobileUseGrantStore.CONFIG_KEY) },
            {
                try {
                    store.globalConfiguration()
                    true
                } catch (
                    _: com.helix.extensions.mobileuse.config.MobileUseConfigurationUnreadable,
                ) {
                    false
                }
            },
            {
                lines.setLines(MobileUseGrantStore.CONFIG_KEY, emptyList())
                lines.setLines(MobileUseGrantStore.EXECUTION_KEY, emptyList())
            },
        )
    }

    private var appContext: Context? = null
    private var runtime: MobileUsePlugin? = null
    private var settingsStore: MobileUseGrantStore? = null

    fun backgroundApplicationName(value: com.helix.app.chat.DisplayExecutionUi): String? {
        val context = appContext
        val name = value.lastObservedPackage
        return if (context == null || name == null) {
            null
        } else {
            try {
                val info = context.packageManager.getApplicationInfo(name, 0)
                context.packageManager
                    .getApplicationLabel(info)
                    .toString()
                    .take(120)
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    fun backgroundPreview(value: com.helix.app.chat.DisplayExecutionUi): android.graphics.Bitmap? =
        runtime?.permissionCenter?.backgroundPreview(value.sessionId, value.turnId, value.generation)

    private fun backgroundDefault(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 31 &&
            com.helix.tools.deviceaccess.DeviceAccess
                .shizukuReady() &&
            com.helix.tools.deviceaccess.DeviceAccess
                .shizukuGranted()

    private fun createGrants(
        read: (String) -> List<String>,
        write: (String, List<String>) -> Unit,
        selection: (String) -> String?,
    ) = MobileUseGrantStore(read, write, selection, defaultBackground = ::backgroundDefault)

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
        taskActive: (String, String) -> Boolean = { _, _ -> false },
        displayPresentation: (com.helix.app.chat.DisplayExecutionUi) -> Unit = {},
        taskHost: com.helix.extensions.plugin.PluginTaskHost? = null,
    ) {
        com.helix.tools.deviceaccess.DeviceAccess
            .configure(
                context,
                "mobile-use",
                com.helix.extensions.mobileuse.automation.backend.RootAutomationService::class.java,
                enabled = { plugins.hasPublishedTools(MobileUsePlugin.PLUGIN_ID) },
            )
        val grants = createGrants(readConfiguration, writeConfiguration, selectionId)
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
                    taskActive = taskActive,
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
        plugin.permissionCenter.displayPresentation = { event ->
            displayPresentation(
                com.helix.app.chat.DisplayExecutionUi(
                    event.sessionId,
                    event.turnId,
                    com.helix.app.chat.DisplayExecutionState
                        .valueOf(event.lifecycle.name),
                    event.lastObservedPackage,
                    event.generation,
                ),
            )
        }
        plugin.permissionCenter.configureConversations(grants, conversationExists, plugin::isAvailable)
        if (plugins.find(com.helix.extensions.chatreply.ChatReplyPlugin.PLUGIN_ID) == null) {
            plugins.register(
                com.helix.extensions.chatreply
                    .ChatReplyPlugin(context.applicationContext),
            )
        }
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
    fun Settings(
        onPermissions: () -> Unit,
        onBack: () -> Unit = {},
        onSaved: () -> Unit = {},
    ) {
        val context = appContext ?: return
        val store = settingsStore ?: return
        MobileUseSettings(context, store, onBack = onBack, onSaved = onSaved, onPermissions = onPermissions)
    }
}
