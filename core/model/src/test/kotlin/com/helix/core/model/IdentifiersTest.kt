package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

private data class IdFactory(
    val kind: String,
    val create: (String) -> Any,
)

private val ID_FACTORIES: List<IdFactory> =
    listOf(
        IdFactory("sessionId") { SessionId(it) },
        IdFactory("messageId") { MessageId(it) },
        IdFactory("turnId") { TurnId(it) },
        IdFactory("modelCallId") { ModelCallId(it) },
        IdFactory("toolCallId") { ToolCallId(it) },
        IdFactory("toolResultId") { ToolResultId(it) },
        IdFactory("approvalId") { ApprovalId(it) },
        IdFactory("executionId") { ExecutionId(it) },
        IdFactory("artifactId") { ArtifactId(it) },
        IdFactory("auditEventId") { AuditEventId(it) },
        IdFactory("providerId") { ProviderId(it) },
        IdFactory("runtimeInstallId") { RuntimeInstallId(it) },
        IdFactory("planId") { PlanId(it) },
        IdFactory("goalId") { GoalId(it) },
        IdFactory("goalRunId") { GoalRunId(it) },
        IdFactory("mcpServerId") { McpServerId(it) },
        IdFactory("skillId") { SkillId(it) },
        IdFactory("executionTargetId") { ExecutionTargetId(it) },
        IdFactory("operationId") { OperationId(it) },
        IdFactory("correlationId") { CorrelationId(it) },
        IdFactory("scopeId") { ScopeId(it) },
        IdFactory("workspaceId") { WorkspaceId(it) },
    )

class IdentifiersTest {
    @Test
    fun allIdTypesArePresent() {
        assertEquals(22, ID_FACTORIES.size)
    }

    @Test
    fun allIdTypesAcceptValidValues() {
        val validValues = listOf("a", "abc-123_456", "A0_Z", "id-99", "a".repeat(64))
        for (factory in ID_FACTORIES) {
            for (value in validValues) {
                val id = factory.create(value)
                assertEquals("${factory.kind} toString", value, id.toString())
                assertEquals("${factory.kind} equality", factory.create(value), id)
                assertEquals("${factory.kind} hashCode", factory.create(value).hashCode(), id.hashCode())
            }
        }
    }

    @Test
    fun allIdTypesRejectInvalidValues() {
        val invalidValues =
            mapOf(
                "empty" to "",
                "too long" to "a".repeat(65),
                "space" to "a b",
                "path slash" to "a/b",
                "backslash" to "a" + '\\' + "b",
                "newline" to "a" + '\n' + "b",
                "carriage return" to "a" + '\r' + "b",
                "tab" to "a" + '\t' + "b",
                "colon" to "a:b",
                "dot" to "a.b",
                "unicode" to "café",
                "symbol" to "a#b",
            )
        for (factory in ID_FACTORIES) {
            for ((label, value) in invalidValues) {
                assertThrows<IllegalArgumentException>("${factory.kind} accepts $label value") {
                    factory.create(value)
                }
            }
        }
    }

    @Test
    fun idTypesAreDistinctFromEachOther() {
        val turn: Any = TurnId("same-value")
        val execution: Any = ExecutionId("same-value")
        org.junit.Assert.assertNotEquals(turn, execution)
    }
}
