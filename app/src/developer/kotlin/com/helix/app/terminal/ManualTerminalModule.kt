package com.helix.app.terminal

import android.content.Context
import com.helix.app.profile.SafetyProfileStore
import com.helix.tools.framework.ExecutionOwnership

internal object ManualTerminalModule {
    fun create(
        context: Context,
        ownership: ExecutionOwnership,
        profile: SafetyProfileStore,
    ): ManualTerminal = DeveloperManualTerminal(context.applicationContext, ownership, profile)
}
