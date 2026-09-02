package com.helix.tools.framework

import com.helix.core.model.Capability
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.core.policy.ApprovalBinding
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * HXA-042 gate (roadmap + ADR-0011): the FIRST non-`time.now` business tools register, so the
 * descriptor approval-invalidation gap must be closed by MECHANICAL TEST, not KDoc. A contract
 * that keeps `(name, version, schemaHash)` constant but changes a security field (timeout, output
 * cap, required capabilities, operation class, base risk, idempotency, or origin) MUST produce a
 * different `ApprovalBinding` hash — so an approval minted for the old contract is a DIFFERENT
 * binding and a stale credential can never authorize the changed one.
 *
 * The registry independently forbids re-registering the same `(name, version)` (a silent
 * overwrite would invalidate approvals), which is the first line. This test is the deeper line:
 * even a stored approval from an earlier contract cannot match the changed binding, because the
 * full security-descriptor `contractHash` — not merely the `(name, version, schemaHash)` triple —
 * is bound into the approval hash.
 */
class ContractHashGateTest {
    // ------------------------------------------------------------------ per-field coverage

    @Test
    fun everySecurityFieldChangeKeepsSchemaHashButChangesContractHash() {
        val base = descriptor()
        val variants =
            listOf(
                "timeout" to base.copy(timeout = 60.seconds),
                "maxOutputBytes" to base.copy(maxOutputBytes = base.maxOutputBytes + 1L),
                "requiredCapabilities" to
                    base.copy(
                        requiredCapabilities = setOf(Capability.WEB_BROWSING),
                    ),
                "operationClass" to base.copy(operationClass = ToolOperationClass.NETWORK),
                "baseRisk" to base.copy(baseRisk = RiskLevel.L3),
                "idempotency" to base.copy(idempotency = Idempotency.NON_IDEMPOTENT),
            )
        variants.forEach { (label, variant) ->
            assertEquals(
                "a $label-only change must not alter the schema contract",
                base.schemaHash.hex,
                variant.schemaHash.hex,
            )
            assertNotEquals(
                "a $label-only change MUST alter the security contract",
                base.contractHash.hex,
                variant.contractHash.hex,
            )
        }
    }

    @Test
    fun changingOnlyAnMcpOriginFieldKeepsSchemaHashButChangesContractHash() {
        // Built-in names are always BuiltInOrigin (constructor-enforced), so origin is varied
        // through an MCP tool: same name/version/schema, different bound server/protocol.
        val base = mcpDescriptor(serverId = "srv", protocolVersion = 1)
        val newerProtocol = base.copy(origin = ToolOrigin.McpOrigin(serverId = "srv", protocolVersion = 2))
        val otherServer = base.copy(origin = ToolOrigin.McpOrigin(serverId = "srv-2", protocolVersion = 1))
        listOf("protocolVersion" to newerProtocol, "serverId" to otherServer).forEach { (label, variant) ->
            assertEquals(
                "an MCP $label change must not alter the schema contract",
                base.schemaHash.hex,
                variant.schemaHash.hex,
            )
            assertNotEquals(
                "an MCP $label change MUST alter the security contract",
                base.contractHash.hex,
                variant.contractHash.hex,
            )
        }
    }

    @Test
    fun changingOnlyUntrustedMcpHintsDoesNotAlterTheContract() {
        // serverProvidedHints are untrusted, display-only: folding them into the approval
        // contract would let a server revoke an approval by editing a hint. The gate is the
        // OPPOSITE — hints must not change the contract hash (nor the schema hash).
        val base = mcpDescriptor(serverId = "srv", protocolVersion = 1)
        val hinted =
            base.copy(
                origin =
                    ToolOrigin.McpOrigin(
                        serverId = "srv",
                        protocolVersion = 1,
                        serverProvidedHints =
                            mapOf("readOnlyHint" to true),
                    ),
            )
        assertEquals(base.schemaHash.hex, hinted.schemaHash.hex)
        assertEquals(
            "server hints are untrusted display text, not contract",
            base.contractHash.hex,
            hinted.contractHash.hex,
        )
    }

    // ---------------------------------------------------- binding-level rejection (the gate)

    @Test
    fun aSecurityFieldOnlyChangeYieldsADifferentApprovalBindingHash() {
        // The whole point: (name, version, schema, scope, session, target, ui, args) held
        // EXACTLY constant, only the contract hash differs -> a different binding hash. A
        // stale approval stored under the base hash cannot consume against the variant.
        val base = descriptor()
        val variants =
            listOf(
                base.copy(timeout = 60.seconds),
                base.copy(maxOutputBytes = base.maxOutputBytes + 1L),
                base.copy(requiredCapabilities = setOf(Capability.WEB_BROWSING)),
                base.copy(operationClass = ToolOperationClass.NETWORK),
                base.copy(baseRisk = RiskLevel.L3),
                base.copy(idempotency = Idempotency.NON_IDEMPOTENT),
            )
        val baseBinding = bindingFor(base)
        variants.forEach { variant ->
            assertEquals(base.schemaHash.hex, variant.schemaHash.hex)
            assertNotEquals(base.contractHash.hex, variant.contractHash.hex)
            val variantBinding = bindingFor(variant)
            // Same identity + scope + session + target + ui + args as the base binding:
            assertEquals(baseBinding.toolName, variantBinding.toolName)
            assertEquals(baseBinding.toolVersion, variantBinding.toolVersion)
            assertEquals(baseBinding.schemaHash, variantBinding.schemaHash)
            assertEquals(baseBinding.scopeRef, variantBinding.scopeRef)
            assertEquals(baseBinding.sessionId, variantBinding.sessionId)
            assertEquals(baseBinding.executionTarget, variantBinding.executionTarget)
            assertEquals(baseBinding.uiToken, variantBinding.uiToken)
            assertEquals(baseBinding.argsHash, variantBinding.argsHash)
            // ... yet a DIFFERENT approval hash: the stale credential does not match.
            assertNotEquals(
                "a security-field-only change MUST be a different approval binding (stale credential rejected)",
                baseBinding.hash,
                variantBinding.hash,
            )
        }
    }

    // ----------------------------------------------------------- determinism + superset

    @Test
    fun equalDescriptorsProduceIdenticalContractHashes() {
        assertEquals(descriptor().contractHash.hex, descriptor().contractHash.hex)
    }

    @Test
    fun theContractHashIsInsensitiveToTheCapabilitySetOrder() {
        // ADR-0011: requiredCapabilities canonicalize BY NAME, so the digest of a capability set
        // must not depend on the order the set literal was written in (Kotlin setOf iterates in
        // insertion order — without name-sorting, these two sets hash differently).
        val abc =
            descriptor(
                requiredCapabilities =
                    setOf(Capability.WEB_BROWSING, Capability.ROOT_SHELL, Capability.CALENDAR_WRITE),
            )
        val cab =
            descriptor(
                requiredCapabilities =
                    setOf(Capability.CALENDAR_WRITE, Capability.ROOT_SHELL, Capability.WEB_BROWSING),
            )
        assertEquals(abc.contractHash.hex, cab.contractHash.hex)
    }

    @Test
    fun aSchemaChangeAlsoChangesTheContractHash() {
        // contractHash is the SUPERSET of schemaHash: a schema change moves both.
        val base = descriptor()
        val reschema =
            descriptor().copy(
                inputSchema = json("""{"type":"object","properties":{"n":{"type":"integer"}}}"""),
            )
        assertNotEquals(base.schemaHash.hex, reschema.schemaHash.hex)
        assertNotEquals(base.contractHash.hex, reschema.contractHash.hex)
    }

    // ---------------------------------------------------------------------- helpers

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun descriptor(requiredCapabilities: Set<Capability> = emptySet()): ToolDescriptor =
        ToolDescriptor(
            name = ToolName("fake"),
            version = ToolVersion(1),
            description = "test tool",
            inputSchema = json("""{"type":"object"}"""),
            outputSchema = json("""{"type":"object"}"""),
            operationClass = ToolOperationClass.LOCAL_MUTATION,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
            maxOutputBytes = 1024L,
            requiredCapabilities = requiredCapabilities,
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )

    private fun mcpDescriptor(
        serverId: String,
        protocolVersion: Int,
    ): ToolDescriptor =
        ToolDescriptor(
            name = ToolName("mcp.$serverId.tool"),
            version = ToolVersion(1),
            description = "mcp tool",
            inputSchema = json("""{"type":"object"}"""),
            outputSchema = json("""{"type":"object"}"""),
            operationClass = ToolOperationClass.NETWORK,
            baseRisk = RiskLevel.L2,
            timeout = 30.seconds,
            maxOutputBytes = 1024L,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.NON_IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.McpOrigin(serverId = serverId, protocolVersion = protocolVersion),
        )

    private fun bindingFor(d: ToolDescriptor): ApprovalBinding =
        ApprovalBinding(
            toolCallId = "call-1",
            toolName = d.name.value,
            toolVersion = d.version.value.toString(),
            schemaHash = d.schemaHash.hex,
            contractHash = d.contractHash.hex,
            scopeRef = "unscoped",
            sessionId = "session-1",
            executionTarget = d.executionTarget,
            uiToken = "ui:card:1",
            argsHash = "b".repeat(64),
        )
}
