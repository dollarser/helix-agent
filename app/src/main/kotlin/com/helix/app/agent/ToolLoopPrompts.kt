package com.helix.app.agent

/** Packaged host prompts; pure Core only reports progress decisions. */
internal object ToolLoopPrompts {
    val EXHAUSTED =
        com.helix.app.chat.packagedPromptTemplates
            .text("loop-exhausted")
            .trim()

    val WARNING =
        com.helix.app.chat.packagedPromptTemplates
            .text("loop-warning")
            .trim()
}
