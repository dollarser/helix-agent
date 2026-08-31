package com.helix.core.policy

import com.helix.core.model.McpServerId
import com.helix.core.model.SkillId

/**
 * The trusted source of the tool being called (architecture doc section 8: tool source, schema
 * hash, snapshot hash are dynamic-risk factors). These facts are produced by the registry and
 * the dispatcher from registered state — never from tool arguments, so model, MCP or Skill
 * content cannot re-source a call to something cheaper (AGENTS.md; ADR-0003: MCP annotation,
 * Skill instruction and static baseRisk can never lower dynamic risk).
 */
sealed interface ToolCallSource {
    /** A built-in tool registered in-process. */
    data object BuiltIn : ToolCallSource

    /**
     * An MCP tool. [toolSchemaHash] is the canonical hash of the registered tool schema; a
     * schema change is a binding-field change and re-gates every prior grant.
     */
    data class Mcp(
        val serverId: McpServerId,
        val toolSchemaHash: String,
    ) : ToolCallSource

    /** A Skill-backed tool; the snapshot hash binds the exact reviewed SKILL.md content. */
    data class Skill(
        val skillId: SkillId,
        val snapshotHash: String,
    ) : ToolCallSource
}
