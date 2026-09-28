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
    ) = Unit

    fun scopeFor(toolName: String?): UserScope? = null

    @Composable
    @Suppress("FunctionName")
    fun Section(profile: SafetyProfile) = Unit
}
