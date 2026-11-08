package com.helix.app.chat

import com.helix.app.goal.GoalLifecycleTools
import com.helix.tools.framework.ToolDescriptor

/**
 * Stable model-surface ordering. Request capacity is governed by context and provider constraints.
 *
 * This does not grant authority or bypass availability/policy. It only prevents essential built-in
 * contracts from disappearing because optional catalogs happened to register earlier. Within each
 * rank, names stabilize the wire prefix when discovery recency changes but membership does not.
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
            "glob",
            "grep",
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
                    "skills.read_resource",
                )
        return shared + preferred + if (preferred.isEmpty()) setOf("code.javascript.run") else emptySet()
    }

    fun prioritize(
        tools: List<ToolDescriptor>,
        preferred: Set<String> = emptySet(),
    ): List<ToolDescriptor> =
        tools
            .sortedWith(
                compareBy<ToolDescriptor> { rank(it, preferred) }.thenBy { it.name.value },
            )

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
