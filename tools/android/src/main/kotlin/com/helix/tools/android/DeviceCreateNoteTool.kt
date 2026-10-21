package com.helix.tools.android

import com.helix.core.model.ToolName
import com.helix.tools.framework.ToolRegistry

/** External note handoff: deliberately does not claim a note was persisted by another app. */
object DeviceCreateNoteTool {
    const val NAME: String = "device.create_note"

    fun descriptor() =
        AndroidShareTool.descriptor().copy(
            name = ToolName(NAME),
            description =
                "Send [text] and optional [title] to a notes app through the system share chooser. " +
                    "Use when the user asks to save a note in another app. The user chooses the app " +
                    "and completes saving; chooser-opened does not mean the note was saved. " +
                    "For IMA or Tencent Docs, use the connected service's direct save tool instead; " +
                    "never substitute this chooser for a direct cloud save.",
        )

    fun register(
        registry: ToolRegistry,
        bridge: AndroidSystemBridge,
    ) {
        registry.register(descriptor(), AndroidShareTool.executor(bridge))
    }
}
