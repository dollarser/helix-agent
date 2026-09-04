package com.helix.tools.framework

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.time.Duration.Companion.seconds

/** Shared test fixtures: minimal valid descriptors for both origins. */
internal object TestFixtures {
    fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject

    fun builtIn(
        name: String = "read",
        version: Int = 1,
        operationClass: ToolOperationClass = ToolOperationClass.READ_ONLY,
        baseRisk: RiskLevel = RiskLevel.L0,
        timeoutSeconds: Long = 30,
        maxOutputBytes: Long = 1024 * 1024,
    ): ToolDescriptor =
        ToolDescriptor(
            name = ToolName(name),
            version = ToolVersion(version),
            description = "test built-in tool",
            inputSchema = json("""{"type":"object"}"""),
            outputSchema = json("""{"type":"object"}"""),
            operationClass = operationClass,
            baseRisk = baseRisk,
            timeout = timeoutSeconds.seconds,
            maxOutputBytes = maxOutputBytes,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    fun mcpSpec(
        serverToolName: String = "search",
        version: Int = 1,
        operationClass: ToolOperationClass = ToolOperationClass.NETWORK,
        baseRisk: RiskLevel = RiskLevel.L1,
        hints: Map<String, Boolean> = emptyMap(),
    ): McpToolSpec =
        McpToolSpec(
            serverToolName = serverToolName,
            version = ToolVersion(version),
            description = "test mcp tool",
            inputSchema = json("""{"type":"object"}"""),
            outputSchema = json("""{"type":"object"}"""),
            operationClass = operationClass,
            baseRisk = baseRisk,
            timeout = 30.seconds,
            maxOutputBytes = 1024 * 1024,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            sourceSchemaHash = "a".repeat(64),
            serverProvidedHints = hints,
        )

    fun a2aSpec(
        skillId: String = "echo-skill",
        skillSlug: String = "echo-skill_deadbeef0000",
        cardHash: String = "b".repeat(64),
        skillHash: String = "c".repeat(64),
    ): A2aToolSpec =
        A2aToolSpec(
            skillId = skillId,
            skillSlug = skillSlug,
            description = "UNTRUSTED_A2A_CONTENT test Skill",
            cardHash = cardHash,
            skillHash = skillHash,
            interfaceOrigin = "https://agent.example:443",
            binding = "JSONRPC",
            protocolVersion = "1.0",
            inputSchema = json("""{"type":"object"}"""),
            outputSchema = json("""{"type":"object"}"""),
            timeout = 30.seconds,
            maxOutputBytes = 1024 * 1024,
        )
}
