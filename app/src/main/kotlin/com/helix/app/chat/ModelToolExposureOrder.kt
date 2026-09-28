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
            "files.search",
        ).withIndex().associate { (index, name) -> name to index }

    fun prioritize(tools: List<ToolDescriptor>): List<ToolDescriptor> =
        tools
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<ToolDescriptor>> { rank(it.value) }.thenBy { it.index },
            ).map { it.value }

    private fun rank(tool: ToolDescriptor): Int {
        val name = tool.name.value
        return when {
            name in GoalLifecycleTools.names || name == ToolResultReadTool.NAME -> 0
            name == "tools.search" -> 10
            name in coreFiles -> 20 + requireNotNull(coreFiles[name])
            else -> 100
        }
    }
}
