@file:Suppress("FunctionName", "UnusedParameter", "FunctionOnlyReturningConstant")

package com.helix.app.automation

import android.content.Context
import androidx.compose.runtime.Composable
import com.helix.core.policy.UserScope
import com.helix.extensions.plugin.PluginRegistry

internal object AutomationModule {
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
    ) = Unit

    fun capabilityState() = com.helix.core.policy.GrantState.UNAVAILABLE

    fun scopeFor(
        toolName: String?,
        conversationId: String?,
    ): UserScope? = null

    @Composable
    fun Settings(onPermissions: () -> Unit) = Unit
}
