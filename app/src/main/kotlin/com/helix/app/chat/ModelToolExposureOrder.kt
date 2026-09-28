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

    // Token actions are unusable without snapshot/find; keep the admitted UI contracts together.
    private val coreUi =
        setOf(
            "ui.snapshot",
            "ui.find",
            "ui.click",
            "ui.long_click",
            "ui.set_text",
            "ui.scroll",
            "ui.back",
            "ui.home",
            "ui.wait",
        )

    fun defaultNames(preferUi: Boolean): Set<String> =
        coreFiles.keys + GoalLifecycleTools.names +
            setOf(
                "tools.search",
                ToolResultReadTool.NAME,
                "code.javascript.run",
                "plan.submit",
                "todo.write",
                "skills.list",
                "skills.enable",
                "skills.disable",
                "skills.read",
                "skills.read_resource",
                "time.now",
            ) + if (preferUi) coreUi else emptySet()

    fun prioritize(
        tools: List<ToolDescriptor>,
        preferUi: Boolean = false,
    ): List<ToolDescriptor> =
        tools
            .withIndex()
            .sortedWith(
                compareBy<IndexedValue<ToolDescriptor>> { rank(it.value, preferUi) }.thenBy { it.index },
            ).map { it.value }

    private fun rank(
        tool: ToolDescriptor,
        preferUi: Boolean,
    ): Int {
        val name = tool.name.value
        return when {
            name in GoalLifecycleTools.names || name == ToolResultReadTool.NAME -> 0
            name == "tools.search" -> 10
            name in coreFiles -> 20 + requireNotNull(coreFiles[name])
            preferUi && name in coreUi -> 30
            else -> 100
        }
    }
}
