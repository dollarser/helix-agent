package com.helix.app.chat

import com.helix.app.goal.GoalLifecycleTools
import com.helix.tools.framework.ToolDescriptor

/**
 * Stable model-surface ordering before the hard 64-tool cap.
 *
 * This does not grant authority or bypass availability/policy. It only prevents essential built-in
 * contracts from disappearing because optional catalogs happened to register earlier.
 */
internal object ModelToolExposureOrder {
    private val coreFiles =
        listOf(
            "read",
            "write",
            "edit",
            "files.list",
            "files.stat",
            "files.mkdir",
            "files.search",
            "view_image",
        ).withIndex().associate { (index, name) -> name to index }

    fun defaultNames(preferred: Set<String>): Set<String> {
        val shared =
            (coreFiles.keys - "files.stat") + GoalLifecycleTools.names +
                setOf(
                    "tools.search",
                    "ask_user",
                    ToolResultReadTool.NAME,
                    "plan.submit",
                    "todo.write",
                    "skills.enable",
                    "skills.read",
                )
        return shared + preferred + if (preferred.isEmpty()) setOf("code.javascript.run") else emptySet()
    }

    fun prioritize(
        tools: List<ToolDescriptor>,
        preferred: Set<String> = emptySet(),
    ): List<ToolDescriptor> =
        tools
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<ToolDescriptor>> { rank(it.value, preferred) }.thenBy { it.index },
            ).map { it.value }

    private fun rank(
        tool: ToolDescriptor,
        preferred: Set<String>,
    ): Int {
        val name = tool.name.value
        return when {
            name in GoalLifecycleTools.names || name == ToolResultReadTool.NAME -> 0
            name == "tools.search" || name == "ask_user" -> 10
            name in coreFiles -> 20 + requireNotNull(coreFiles[name])
            name in preferred -> 30
            else -> 100
        }
    }
}
