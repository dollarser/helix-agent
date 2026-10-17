package com.helix.app.mcp

import com.helix.core.model.ToolName
import com.helix.tools.framework.ToolDescriptor

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

    /** Expand only the admitted snapshot. A declaration neither enables nor executes its companions. */
    fun complete(
        matches: List<ToolDescriptor>,
        available: List<ToolDescriptor>,
    ): List<ToolDescriptor> {
        val selected = linkedMapOf<ToolName, ToolDescriptor>()
        val pending = ArrayDeque(matches.filter { it in available })
        while (pending.isNotEmpty()) {
            val tool = pending.removeFirst()
            if (selected.putIfAbsent(tool.name, tool) != null) continue
            pending.addAll(
                available.filter { candidate ->
                    candidate.origin == tool.origin && candidate.name in tool.discoveryCompanions &&
                        candidate.name !in selected
                },
            )
        }
        return selected.values.toList()
    }
}
