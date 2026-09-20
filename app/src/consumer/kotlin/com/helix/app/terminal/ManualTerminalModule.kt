package com.helix.app.terminal

import android.content.Context
import com.helix.app.profile.SafetyProfileStore
import com.helix.tools.framework.ExecutionOwnership

internal object ManualTerminalModule {
    @Suppress("UnusedParameter") // No terminal Activity or renderer is packaged in consumer.
    fun open(
        context: Context,
        directory: String,
    ) = Unit

    @Suppress("UnusedParameter", "FunctionOnlyReturningConstant") // Deliberate consumer flavor seam.
    fun create(
        context: Context,
        ownership: ExecutionOwnership,
        profile: SafetyProfileStore,
    ): ManualTerminal? = null
}
