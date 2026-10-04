@file:Suppress("FunctionName", "UnusedParameter", "FunctionOnlyReturningConstant")

package com.helix.app.automation

import android.content.Context
import androidx.compose.runtime.Composable
import com.helix.core.policy.UserScope
import com.helix.extensions.plugin.PluginRegistry

internal object AutomationModule {
    fun register(
        context: Context,
        plugins: PluginRegistry,
        images: com.helix.tools.framework.ToolImagePublication,
        grants: com.helix.core.policy.MobileUseGrantStore,
        conversationExists: (String) -> Boolean,
        taskHost: com.helix.extensions.plugin.PluginTaskHost? = null,
    ) = Unit

    fun capabilityState() = com.helix.core.policy.GrantState.UNAVAILABLE

    fun scopeFor(
        toolName: String?,
        conversationId: String?,
    ): UserScope? = null

    fun owns(descriptor: com.helix.tools.framework.ToolDescriptor?): Boolean = false

    fun dispatchScopeFor(
        descriptor: com.helix.tools.framework.ToolDescriptor?,
        conversationId: String?,
    ): UserScope? = null

    fun skillContext(): String? = null

    @Composable
    fun Settings(onPermissions: () -> Unit) = Unit

    @Composable
    fun Permissions() = Unit
}
