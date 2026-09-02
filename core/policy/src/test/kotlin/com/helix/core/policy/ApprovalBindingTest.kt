package com.helix.core.policy

import com.helix.core.model.ExecutionTargetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * HXA-034: ApprovalBinding canonical JSON + hash — deterministic, field-sensitive
 * (replay to any other session/workspace/args/target/version/schema/UI token fails) and
 * fail-closed at construction.
 */
class ApprovalBindingTest {
    private val base =
        binding(
            toolCallId = "toolcall-1",
            toolName = "bash",
            toolVersion = "1",
            schemaHash = "a".repeat(64),
            contractHash = "f".repeat(64),
            scopeRef = "workspace:ws-1",
            sessionId = "session-1",
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            uiToken = "ui:approval-page:tok-1",
            argsHash = "b".repeat(64),
        )

    @Test
    fun hashIsDeterministicAcrossInstances() {
        val other =
            binding(
                toolCallId = "toolcall-1",
                toolName = "bash",
                toolVersion = "1",
                schemaHash = "a".repeat(64),
                contractHash = "f".repeat(64),
                scopeRef = "workspace:ws-1",
                sessionId = "session-1",
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                uiToken = "ui:approval-page:tok-1",
                argsHash = "b".repeat(64),
            )
        assertEquals(base, other)
        assertEquals(base.hash, other.hash)
        assertEquals(base.canonicalJson, other.canonicalJson)
        assertTrue(base.hash.length == 64)
        assertTrue(base.hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun changingAnyBindingFieldChangesTheHash() {
        val original = base.hash
        assertDifferentHash(base.copy(toolCallId = "toolcall-2"), original)
        assertDifferentHash(base.copy(toolName = "rm"), original)
        assertDifferentHash(base.copy(toolVersion = "2"), original)
        assertDifferentHash(base.copy(schemaHash = "c".repeat(64)), original)
        // HXA-042 (ADR-0011): the full security-descriptor contract is bound, so a contract
        // change (even one that keeps name/version/schema constant) is a different binding.
        assertDifferentHash(base.copy(contractHash = "e".repeat(64)), original)
        assertDifferentHash(base.copy(scopeRef = "workspace:ws-2"), original)
        assertDifferentHash(base.copy(sessionId = "session-2"), original)
        assertDifferentHash(base.copy(executionTarget = ExecutionTargetType.LOCAL_QUICKJS), original)
        assertDifferentHash(base.copy(uiToken = "ui:approval-page:tok-2"), original)
        assertDifferentHash(base.copy(argsHash = "d".repeat(64)), original)
    }

    @Test
    fun canonicalJsonUsesSortedKeysAndFullEscaping() {
        val schemaHash = "a".repeat(64)
        val contractHash = "f".repeat(64)
        val argsHash = "b".repeat(64)
        val expected =
            """{"argsHash":"$argsHash","contractHash":"$contractHash","executionTarget":"LOCAL_ANDROID","""" +
                """scopeRef":"workspace:ws-1","schemaHash":"$schemaHash","""" +
                """sessionId":"session-1","toolCallId":"toolcall-1","toolName":"bash","""" +
                """toolVersion":"1","uiToken":"ui:approval-page:tok-1"}"""
        assertEquals(expected, base.canonicalJson)

        // quotes, backslashes and control characters must be escaped deterministically
        val tricky = base.copy(scopeRef = "we\"ird\\path\nx")
        assertTrue(tricky.canonicalJson.contains("\"scopeRef\":\"we\\\"ird\\\\path\\u000ax\""))
        assertDifferentHash(tricky, base.hash)
    }

    @Test
    fun constructionFailsClosedOnMalformedFacts() {
        assertIllegal { base.copy(toolCallId = "") }
        assertIllegal { base.copy(toolCallId = "x".repeat(65)) }
        assertIllegal { base.copy(toolName = "") }
        assertIllegal { base.copy(toolName = "x".repeat(129)) }
        assertIllegal { base.copy(toolVersion = "") }
        assertIllegal { base.copy(toolVersion = "x".repeat(33)) }
        assertIllegal { base.copy(schemaHash = "a".repeat(63)) }
        assertIllegal { base.copy(schemaHash = "A".repeat(64)) }
        assertIllegal { base.copy(schemaHash = "g".repeat(64)) }
        assertIllegal { base.copy(contractHash = "f".repeat(63)) }
        assertIllegal { base.copy(contractHash = "F".repeat(64)) }
        assertIllegal { base.copy(contractHash = "g".repeat(64)) }
        assertIllegal { base.copy(scopeRef = "") }
        assertIllegal { base.copy(scopeRef = "x".repeat(ApprovalBinding.MAX_SCOPE_REF_LENGTH + 1)) }
        assertIllegal { base.copy(sessionId = "") }
        assertIllegal { base.copy(uiToken = "") }
        assertIllegal { base.copy(uiToken = "x".repeat(129)) }
        assertIllegal { base.copy(argsHash = "b".repeat(63)) }
    }

    @Test
    fun proofRequiresTheStoredHashShape() {
        val proof = ApprovalProof(base.hash, base.hash)
        assertEquals(base.hash, proof.bindingHash)
        assertIllegal { ApprovalProof("", base.hash) }
        assertIllegal { ApprovalProof("approval-1", "a".repeat(63)) }
        assertIllegal { ApprovalProof("approval-1", "A".repeat(64)) }
    }

    private fun assertDifferentHash(
        binding: ApprovalBinding,
        original: String,
    ) {
        assertNotEquals(original, binding.hash)
    }

    private fun assertIllegal(block: () -> Unit) {
        try {
            block()
            fail("expected IllegalArgumentException")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().isNotBlank())
        }
    }

    // Ten named fields: the helper mirrors the ApprovalBinding contract field-by-field.
    @Suppress("LongParameterList")
    private fun binding(
        toolCallId: String,
        toolName: String,
        toolVersion: String,
        schemaHash: String,
        contractHash: String,
        scopeRef: String,
        sessionId: String,
        executionTarget: ExecutionTargetType,
        uiToken: String,
        argsHash: String,
    ) = ApprovalBinding(
        toolCallId = toolCallId,
        toolName = toolName,
        toolVersion = toolVersion,
        schemaHash = schemaHash,
        contractHash = contractHash,
        scopeRef = scopeRef,
        sessionId = sessionId,
        executionTarget = executionTarget,
        uiToken = uiToken,
        argsHash = argsHash,
    )
}
