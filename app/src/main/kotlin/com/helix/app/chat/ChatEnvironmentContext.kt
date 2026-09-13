package com.helix.app.chat

import com.helix.core.model.AgentMode
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRole
import com.helix.core.workspace.FileScopePath

/** Only packaged, allowlisted templates can become harness system instructions. */
internal object ChatEnvironmentContext {
    fun messages(
        directory: FileScopePath,
        mode: AgentMode,
        fileToolsAvailable: Boolean,
    ): List<ModelMessage> {
        val selected =
            buildList {
                add("base")
                if (fileToolsAvailable) add("files")
                if (mode == AgentMode.PLAN) add("plan")
            }
        return selected.map { name ->
            val text =
                checkNotNull(javaClass.getResourceAsStream("/prompts/$name.md")) {
                    "Missing packaged prompt: $name"
                }.bufferedReader().use { it.readText() }
            ModelMessage(ModelRole.SYSTEM, text.replace("{{working_directory}}", directory.toModelReference()))
        }
    }
}
