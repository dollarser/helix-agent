@file:Suppress("UnusedParameter", "FunctionOnlyReturningConstant")

package com.helix.app.root

import android.content.Context
import androidx.compose.runtime.Composable
import com.helix.core.model.Clock
import com.helix.core.model.SafetyProfile
import com.helix.core.policy.GrantState
import com.helix.core.policy.UserScope
import com.helix.tools.framework.ToolImplementationRegistry
import com.helix.tools.framework.ToolRegistry

/** Consumer distribution has no libsu dependency, Root registry entries or Root UI. */
internal object RootModule {
    fun register(
        context: Context,
        clock: Clock,
        registry: ToolRegistry,
        implementations: ToolImplementationRegistry,
    ) = Unit

    fun capabilityState(): GrantState = GrantState.UNAVAILABLE

    fun scopeFor(toolName: String?): UserScope? = null

    @Composable
    @Suppress("FunctionName")
    fun Section(profile: SafetyProfile) = Unit
}
