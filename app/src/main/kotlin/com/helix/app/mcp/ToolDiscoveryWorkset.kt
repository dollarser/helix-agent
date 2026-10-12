package com.helix.app.mcp

import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolOrigin

/** Exposure-only byte budgets. New discoveries remain complete; context admission is authoritative. */
internal object ToolDiscoveryWorkset {
    private const val RECENT_SCHEMA_BYTES = 64 * 1024L
    private const val EAGER_MCP_BYTES = 16 * 1024L
    private const val SCHEMA_FRAME_BYTES = 128L

    fun bytes(tool: ToolDescriptor): Long =
        tool.name.value
            .toByteArray(Charsets.UTF_8)
            .size
            .toLong() +
            tool.description.toByteArray(Charsets.UTF_8).size +
            tool.inputSchema
                .toString()
                .toByteArray(Charsets.UTF_8)
                .size + SCHEMA_FRAME_BYTES

    fun retain(
        matches: List<ToolDescriptor>,
        previous: List<ToolDescriptor>,
        available: List<ToolDescriptor>,
    ): List<ToolDescriptor> {
        val selected = complete(matches, available).toMutableList()
        var used = selected.sumOf(::bytes)
        previous.forEach { tool ->
            val group = complete(listOf(tool), available).filter { it !in selected }
            val cost = group.sumOf(::bytes)
            if (used + cost <= RECENT_SCHEMA_BYTES) {
                selected += group
                used += cost
            }
        }
        return selected
    }

    fun eagerMcp(tools: List<ToolDescriptor>): List<ToolDescriptor> {
        val selected = mutableListOf<ToolDescriptor>()
        var remaining = EAGER_MCP_BYTES
        tools.groupBy { ToolDiscoveryMetadata.of(it).source }.toSortedMap().values.forEach { group ->
            val cost = group.sumOf(::bytes)
            if (cost <= remaining) {
                selected += group
                remaining -= cost
            }
        }
        return selected
    }

    fun complete(
        matches: List<ToolDescriptor>,
        available: List<ToolDescriptor>,
    ): List<ToolDescriptor> =
        (
            matches +
                matches.flatMap { tool ->
                    val names = prerequisites(tool)
                    available.filter { it.name.value in names && it.origin == tool.origin }
                }
        ).distinctBy { it.name }

    private fun prerequisites(tool: ToolDescriptor): Set<String> =
        when (val origin = tool.origin) {
            is ToolOrigin.PluginOrigin -> {
                when (origin.pluginId) {
                    "mobile-use" -> {
                        if (tool.name.value == "ui.gesture") {
                            setOf("ui.screenshot", "ui.device")
                        } else {
                            setOf("ui.snapshot", "ui.find")
                        }
                    }

                    "browser" -> {
                        setOf("browser.snapshot", "browser.find")
                    }

                    else -> {
                        emptySet()
                    }
                }
            }

            ToolOrigin.BuiltInOrigin -> {
                if (tool.name.value.startsWith("code.linux.job.")) {
                    setOf("code.linux.job.status", "code.linux.job.collect", "code.linux.job.cancel", "jobs.await")
                } else {
                    emptySet()
                }
            }

            else -> {
                emptySet()
            }
        }
}
