package com.helix.app.mcp

import com.helix.app.application.tools.ToolModelSchemaProjection
import com.helix.core.agent.ModelInputEstimate
import com.helix.core.agent.TokenEstimator
import com.helix.core.model.ToolName
import com.helix.tools.framework.ToolDescriptor

/** Exposure-only byte budgets. New discoveries remain complete; context admission is authoritative. */
internal object ToolDiscoveryWorkset {
    private const val RECENT_SCHEMA_BYTES = 64 * 1024L
    private const val EAGER_MCP_BYTES = 16 * 1024L

    /** Includes path guidance and presentation metadata through the actual request projection. */
    fun bytes(tool: ToolDescriptor): Long = ModelInputEstimate.toolBytes(ToolModelSchemaProjection.project(tool))

    fun retain(
        matches: List<ToolDescriptor>,
        previous: List<ToolDescriptor>,
        available: List<ToolDescriptor>,
    ): List<ToolDescriptor> {
        val selected = complete(matches, available).toMutableList()
        val costs = mutableMapOf<ToolDescriptor, Long>()

        fun cost(tool: ToolDescriptor): Long = costs.getOrPut(tool) { bytes(tool) }
        var used = selected.sumOf(::cost)
        previous.forEach { tool ->
            val group = complete(listOf(tool), available).filter { it !in selected }
            val groupCost = group.sumOf(::cost)
            if (used + groupCost <= RECENT_SCHEMA_BYTES) {
                selected += group
                used += groupCost
            }
        }
        return selected
    }

    fun eagerMcp(tools: List<ToolDescriptor>): List<ToolDescriptor> {
        val selected = mutableListOf<ToolDescriptor>()
        var remaining = EAGER_MCP_BYTES
        tools.groupBy { ToolDiscoveryMetadata.of(it).source }.toSortedMap().values.forEach { group ->
            // A lower bound avoids projecting large, unselected catalogs (including unsupported
            // presentation-key collisions). Admission still uses the complete final projection.
            val lowerBound =
                group.sumOf {
                    TokenEstimator.utf8Bytes(it.name.value) +
                        ModelInputEstimate.toolBytes(it.description, it.inputSchema.toString())
                }
            if (lowerBound <= remaining) {
                val cost = group.sumOf(::bytes)
                if (cost <= remaining) {
                    selected += group
                    remaining -= cost
                }
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
