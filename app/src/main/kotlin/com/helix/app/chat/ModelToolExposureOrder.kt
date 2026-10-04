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
            "view_image",
        ).withIndex().associate { (index, name) -> name to index }

    // Active Mobile Use should expose the semantic control surface plus the existing direct URL opener.
    private val coreUi =
        setOf(
            "ui.apps",
            "ui.launch",
            "ui.snapshot",
            "ui.find",
            "ui.click",
            "ui.click_match",
            "ui.set_text",
            "ui.ime_enter",
            "ui.scroll",
            "ui.back",
            "ui.wait",
            "ui.device",
            "ui.screenshot",
            "ui.gesture",
            "android.open_uri",
            "http.fetch",
        )

    fun defaultNames(preferUi: Boolean): Set<String> {
        val shared =
            coreFiles.keys + GoalLifecycleTools.names +
                setOf(
                    "tools.search",
                    "ask_user",
                    ToolResultReadTool.NAME,
                    "plan.submit",
                    "todo.write",
                    "skills.enable",
                    "skills.read",
                )
        return shared +
            if (preferUi) {
                coreUi - setOf("http.fetch", "ui.ime_enter")
            } else {
                setOf("code.javascript.run")
            }
    }

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
            name == "tools.search" || name == "ask_user" -> 10
            name in coreFiles -> 20 + requireNotNull(coreFiles[name])
            preferUi && name in coreUi -> 30
            else -> 100
        }
    }
}
