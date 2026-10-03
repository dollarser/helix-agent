@file:Suppress("UnusedParameter", "FunctionOnlyReturningConstant")

package com.helix.app.automation

import android.content.Context
import androidx.compose.runtime.Composable
import com.helix.core.model.SafetyProfile
import com.helix.core.policy.UserScope
import com.helix.extensions.plugin.PluginRegistry

internal object AutomationModule {
    fun register(
        context: Context,
        plugins: PluginRegistry,
        images: com.helix.tools.framework.ToolImagePublication,
        grants: com.helix.core.policy.MobileUseGrantStore,
        conversationExists: (String) -> Boolean,
        screenTarget: suspend (String) -> com.helix.app.vision.MobileUseScreenTarget?,
        taskHost: com.helix.extensions.plugin.PluginTaskHost? = null,
    ) = Unit

    fun scopeFor(
        toolName: String?,
        conversationId: String?,
    ): UserScope? = null

    @Composable
    @Suppress("FunctionName")
    fun Section(
        profile: SafetyProfile,
        conversationId: String? = null,
        prepareConversation: suspend (String) -> Boolean = { true },
    ) = Unit
}
