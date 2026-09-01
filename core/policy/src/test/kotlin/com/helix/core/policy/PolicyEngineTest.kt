package com.helix.core.policy

import com.helix.core.model.AgentMode
import com.helix.core.model.Capability
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.McpServerId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderId
import com.helix.core.model.RiskLevel
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolOperationClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * HXA-033: the Policy Engine — dynamic risk composition (architecture doc section 8) and the
 * ADR-0005 gates: STANDARD per-call confirmation for high-sensitivity egress, ADVANCED
 * exactly-bound time-boxed revocable rules, clock-rollback fail-closed, default denials, and
 * the API-shape guarantees that model/MCP/Skill cannot switch the profile, create LAN scopes
 * or lower residence.
 */
class PolicyEngineTest {
    private val now = Instant.parse("2026-09-01T12:00:00Z")
    private val clock = FixedClock(now)
    private val engine = PolicyEngine(clock)

    private val provider = EgressTarget.Provider(ProviderId("provider-1"))
    private val publicEndpoint = NormalizedEndpoint.parse("https://api.example.com/v1")
    private val lanEndpoint = NormalizedEndpoint.parse("http://192.168.1.10:11434")
    private val loopbackEndpoint = NormalizedEndpoint.parse("http://127.0.0.1:11434")
    private val unknownEndpoint = NormalizedEndpoint.parse("https://203.0.113.7")
    private val metadataEndpoint = NormalizedEndpoint.parse("http://169.254.169.254/latest/meta-data")

    private val scope = WorkspaceScope("ws-1")

    // The parameter list mirrors the full PolicyInput fact set (roadmap HXA-033 lists every
    // composed factor); a builder object would add indirection without narrowing the contract.
    @Suppress("LongParameterList")
    private fun input(
        baseRisk: RiskLevel = RiskLevel.L1,
        operationClass: ToolOperationClass = ToolOperationClass.NETWORK,
        mode: AgentMode = AgentMode.ACT,
        profile: SafetyProfile = SafetyProfile.STANDARD,
        source: ToolCallSource = ToolCallSource.BuiltIn,
        executionTarget: ExecutionTargetType = ExecutionTargetType.LOCAL_ANDROID,
        dataOrigin: DataOrigin = DataOrigin.NETWORK,
        scope: UserScope? = null,
        overwritesExisting: Boolean = false,
        codeOrCommandChanged: Boolean = false,
        sourceBindingChanged: Boolean = false,
        missingCapabilities: Set<Capability> = emptySet(),
        egress: EgressRequest? = null,
        originSeenInSession: Boolean = true,
        lanScopes: Set<NetworkOriginScope> = emptySet(),
    ) = PolicyInput(
        baseRisk = baseRisk,
        operationClass = operationClass,
        mode = mode,
        profile = profile,
        source = source,
        executionTarget = executionTarget,
        dataOrigin = dataOrigin,
        scope = scope,
        overwritesExisting = overwritesExisting,
        codeOrCommandChanged = codeOrCommandChanged,
        sourceBindingChanged = sourceBindingChanged,
        missingCapabilities = missingCapabilities,
        egress = egress,
        originSeenInSession = originSeenInSession,
        lanScopes = lanScopes,
    )

    private fun egress(
        target: EgressTarget = provider,
        endpoint: NormalizedEndpoint = publicEndpoint,
        sensitivity: DataSensitivity = DataSensitivity.NORMAL,
    ) = EgressRequest(target, endpoint, sensitivity)

    private fun rule(
        target: EgressTarget = provider,
        endpoint: NormalizedEndpoint = publicEndpoint,
        ruleScope: UserScope = scope,
        created: Instant = now.minus(Duration.ofHours(1)),
        ttl: Duration = Duration.ofHours(24),
    ) = HighSensitivityRule(
        target,
        endpoint,
        DataSensitivity.SENSITIVE,
        ruleScope,
        created,
        created.plus(ttl),
    )

    private fun denialOf(evaluation: PolicyEvaluation): PolicyDecision.Deny {
        val decision = evaluation.decision
        if (decision !is PolicyDecision.Deny) error("expected Deny, got $decision")
        return decision
    }

    private fun approvalOf(evaluation: PolicyEvaluation): PolicyDecision.RequiresApproval {
        val decision = evaluation.decision
        if (decision !is PolicyDecision.RequiresApproval) error("expected RequiresApproval, got $decision")
        return decision
    }

    // --- baseline decisions -------------------------------------------------------

    @Test
    fun lowRiskCallIsAllowedWithoutFactors() {
        val evaluation = engine.evaluate(input(baseRisk = RiskLevel.L0, operationClass = ToolOperationClass.READ_ONLY))
        assertEquals(PolicyDecision.Allow, evaluation.decision)
        assertEquals(RiskLevel.L0, evaluation.dynamicRisk)
        assertTrue(evaluation.riskFactors.isEmpty())
    }

    @Test
    fun l2BaseRequiresApprovalAndL3IsDeniedByDefault() {
        val l2 = engine.evaluate(input(baseRisk = RiskLevel.L2))
        approvalOf(l2)
        assertEquals(RiskLevel.L2, l2.dynamicRisk)

        val l3 = engine.evaluate(input(baseRisk = RiskLevel.L3))
        assertEquals(PolicyDenialCode.L3_DEFAULT_DENY, denialOf(l3).code)
    }

    @Test
    fun dynamicRiskNeverBelowBaseRisk() {
        val factorCombinations =
            (0 until 32).map { i ->
                booleanArrayOf(i and 1 != 0, i and 2 != 0, i and 4 != 0, i and 8 != 0, i and 16 != 0)
            }
        for (base in listOf(RiskLevel.L0, RiskLevel.L1, RiskLevel.L2)) {
            for (combo in factorCombinations) {
                val overwrite = combo[0]
                val codeChanged = combo[1]
                val bindingChanged = combo[2]
                val newOrigin = combo[3]
                val evaluation =
                    engine.evaluate(
                        input(
                            baseRisk = base,
                            egress = egress(),
                            overwritesExisting = overwrite,
                            codeOrCommandChanged = codeChanged,
                            sourceBindingChanged = bindingChanged,
                            originSeenInSession = !newOrigin,
                        ),
                    )
                assertTrue(
                    "risk ${evaluation.dynamicRisk} below base $base " +
                        "(overwrite=$overwrite, code=$codeChanged, binding=$bindingChanged, new=$newOrigin)",
                    evaluation.dynamicRisk >= base,
                )
            }
        }
    }

    @Test
    fun missingCapabilitiesAreDeniedByDefault() {
        val evaluation =
            engine.evaluate(input(missingCapabilities = setOf(Capability.ROOT_SHELL)))
        assertEquals(PolicyDenialCode.CAPABILITY_NOT_GRANTED, denialOf(evaluation).code)
    }

    // --- mode and execution target -------------------------------------------------

    @Test
    fun planModeMutationIsDeniedByOperationClass() {
        val evaluation =
            engine.evaluate(input(mode = AgentMode.PLAN, operationClass = ToolOperationClass.LOCAL_MUTATION))
        assertEquals(PolicyDenialCode.PLAN_MODE_NOT_READ_ONLY, denialOf(evaluation).code)
    }

    @Test
    fun planModeReadOnlyAtLowRiskIsAllowed() {
        val evaluation =
            engine.evaluate(
                input(mode = AgentMode.PLAN, operationClass = ToolOperationClass.READ_ONLY, baseRisk = RiskLevel.L0),
            )
        assertEquals(PolicyDecision.Allow, evaluation.decision)
    }

    @Test
    fun advancedOnlyRuntimesAreDeniedUnderStandard() {
        val proot =
            engine.evaluate(input(executionTarget = ExecutionTargetType.LOCAL_PROOT))
        assertEquals(PolicyDenialCode.ISOLATED_RUNTIME_REQUIRES_ADVANCED, denialOf(proot).code)

        val cli = engine.evaluate(input(executionTarget = ExecutionTargetType.LOCAL_CLI_RUNTIME))
        assertEquals(PolicyDenialCode.ISOLATED_RUNTIME_REQUIRES_ADVANCED, denialOf(cli).code)
    }

    @Test
    fun advancedOnlyRuntimesAreAllowedUnderAdvanced() {
        val evaluation =
            engine.evaluate(
                input(profile = SafetyProfile.ADVANCED, executionTarget = ExecutionTargetType.LOCAL_PROOT),
            )
        assertEquals(PolicyDecision.Allow, evaluation.decision)
    }

    @Test
    fun quickJsIsProfileIndependent() {
        val evaluation = engine.evaluate(input(executionTarget = ExecutionTargetType.LOCAL_QUICKJS))
        assertFalse(evaluation.decision is PolicyDecision.Deny)
    }

    // --- egress gates (ADR-0005) ---------------------------------------------------

    @Test
    fun forbiddenEgressIsAlwaysDeniedInBothProfiles() {
        val standard = engine.evaluate(input(egress = egress(sensitivity = DataSensitivity.FORBIDDEN)))
        assertEquals(PolicyDenialCode.CREDENTIALS_ALWAYS_DENIED, denialOf(standard).code)

        // even under ADVANCED with a matching rule set — FORBIDDEN cannot be ruled
        val advanced =
            engine.evaluate(
                input(
                    profile = SafetyProfile.ADVANCED,
                    egress = egress(sensitivity = DataSensitivity.FORBIDDEN),
                    scope = scope,
                ),
                setOf(rule()),
            )
        assertEquals(PolicyDenialCode.CREDENTIALS_ALWAYS_DENIED, denialOf(advanced).code)
    }

    @Test
    fun sensitiveEgressUnderStandardAlwaysConfirmsEvenWithMatchingRule() {
        val evaluation =
            engine.evaluate(
                input(egress = egress(sensitivity = DataSensitivity.SENSITIVE), scope = scope),
                setOf(rule()),
            )
        val approval = approvalOf(evaluation)
        assertTrue(approval.detail.contains("provider-1"))
        assertTrue(approval.detail.contains("https://api.example.com:443"))
        assertEquals(DataSensitivity.SENSITIVE, evaluation.effectiveDataCategory)
        // The rule exists but does NOT cover a STANDARD call: nothing may be displayed as
        // a covering bounded rule.
        assertNull(evaluation.matchedEgressRule)
    }

    @Test
    fun sensitiveEgressUnderAdvancedWithoutRuleConfirms() {
        val evaluation =
            engine.evaluate(
                input(
                    profile = SafetyProfile.ADVANCED,
                    egress = egress(sensitivity = DataSensitivity.SENSITIVE),
                    scope = scope,
                ),
            )
        approvalOf(evaluation)
        assertNull(evaluation.matchedEgressRule)
    }

    @Test
    fun sensitiveEgressUnderAdvancedWithLiveMatchingRuleIsAllowed() {
        val live = rule()
        val evaluation =
            engine.evaluate(
                input(
                    profile = SafetyProfile.ADVANCED,
                    egress = egress(sensitivity = DataSensitivity.SENSITIVE),
                    scope = scope,
                ),
                setOf(live),
            )
        assertEquals(PolicyDecision.Allow, evaluation.decision)
        assertTrue(evaluation.riskFactors.any { it.contains("ADVANCED high-sensitivity rule active") })
        // The covering rule is surfaced (HXA-036: the card must show it as a BOUNDED rule).
        assertSame(live, evaluation.matchedEgressRule)
    }

    @Test
    fun ruleClockRollbackFailsClosed() {
        val futureRule = rule(created = now.plus(Duration.ofHours(1)))
        val evaluation =
            engine.evaluate(
                input(
                    profile = SafetyProfile.ADVANCED,
                    egress = egress(sensitivity = DataSensitivity.SENSITIVE),
                    scope = scope,
                ),
                setOf(futureRule),
            )
        approvalOf(evaluation)
    }

    @Test
    fun ruleExpiryFailsClosed() {
        val expired = rule(created = now.minus(Duration.ofHours(25)))
        val evaluation =
            engine.evaluate(
                input(
                    profile = SafetyProfile.ADVANCED,
                    egress = egress(sensitivity = DataSensitivity.SENSITIVE),
                    scope = scope,
                ),
                setOf(expired),
            )
        approvalOf(evaluation)
    }

    @Test
    fun anyBindingFieldChangeReGatesTheCall() {
        val base =
            input(
                profile = SafetyProfile.ADVANCED,
                egress = egress(sensitivity = DataSensitivity.SENSITIVE),
                scope = scope,
            )

        // origin path changed
        val otherPath =
            base.copy(
                egress =
                    egress(
                        sensitivity = DataSensitivity.SENSITIVE,
                        endpoint = NormalizedEndpoint.parse("https://api.example.com/other"),
                    ),
            )
        approvalOf(engine.evaluate(otherPath, setOf(rule())))

        // origin port changed
        val otherPort =
            base.copy(
                egress =
                    egress(
                        sensitivity = DataSensitivity.SENSITIVE,
                        endpoint = NormalizedEndpoint.parse("https://api.example.com:8443/v1"),
                    ),
            )
        approvalOf(engine.evaluate(otherPort, setOf(rule())))

        // target changed
        val otherTarget =
            base.copy(
                egress =
                    egress(
                        sensitivity = DataSensitivity.SENSITIVE,
                        target = EgressTarget.Mcp(McpServerId("mcp-server-1")),
                    ),
            )
        approvalOf(engine.evaluate(otherTarget, setOf(rule())))

        // scope changed
        val otherScope = base.copy(scope = WorkspaceScope("ws-2"))
        approvalOf(engine.evaluate(otherScope, setOf(rule())))

        // no scope on the call
        val noScope = base.copy(scope = null)
        approvalOf(engine.evaluate(noScope, setOf(rule())))
    }

    @Test
    fun ruleRevocationAndNoSlidingRenewal() {
        val live = rule()
        val base =
            input(
                profile = SafetyProfile.ADVANCED,
                egress = egress(sensitivity = DataSensitivity.SENSITIVE),
                scope = scope,
            )

        assertEquals(PolicyDecision.Allow, engine.evaluate(base, setOf(live)).decision)

        // revocation: the rule leaves the set -> the very next call re-gates
        approvalOf(engine.evaluate(base, emptySet()))

        // no sliding renewal: calls at later times never move expiresAt
        val later = now.plus(Duration.ofHours(20))
        assertEquals(PolicyDecision.Allow, PolicyEngine(FixedClock(later)).evaluate(base, setOf(live)).decision)
        val atExpiry = live.expiresAt
        approvalOf(PolicyEngine(FixedClock(atExpiry)).evaluate(base, setOf(live)))
        assertEquals(now.minus(Duration.ofHours(1)).plus(Duration.ofHours(24)), live.expiresAt)
    }

    @Test
    fun lanAndLoopbackEgressUnderStandardAreDenied() {
        val lan = engine.evaluate(input(egress = egress(endpoint = lanEndpoint)))
        assertEquals(PolicyDenialCode.LAN_NOT_ALLOWED, denialOf(lan).code)

        val loopback = engine.evaluate(input(egress = egress(endpoint = loopbackEndpoint)))
        assertEquals(PolicyDenialCode.LAN_NOT_ALLOWED, denialOf(loopback).code)
    }

    @Test
    fun lanEgressUnderAdvancedRequiresAnExactScope() {
        val without = engine.evaluate(input(profile = SafetyProfile.ADVANCED, egress = egress(endpoint = lanEndpoint)))
        assertEquals(PolicyDenialCode.LAN_NOT_ALLOWED, denialOf(without).code)

        val wrongPort =
            engine.evaluate(
                input(
                    profile = SafetyProfile.ADVANCED,
                    egress = egress(endpoint = lanEndpoint),
                    lanScopes = setOf(NetworkOriginScope("192.168.1.10", 9999)),
                ),
            )
        assertEquals(PolicyDenialCode.LAN_NOT_ALLOWED, denialOf(wrongPort).code)

        val exact =
            engine.evaluate(
                input(
                    profile = SafetyProfile.ADVANCED,
                    egress = egress(endpoint = lanEndpoint),
                    lanScopes = setOf(NetworkOriginScope("192.168.1.10", 11434)),
                ),
            )
        assertEquals(PolicyDecision.Allow, exact.decision)
        assertTrue(exact.riskFactors.any { it.contains("192.168.1.10:11434") })
    }

    @Test
    fun reservedMetadataEndpointsAreAlwaysDenied() {
        // even ADVANCED with a scope for the metadata host
        val advanced =
            engine.evaluate(
                input(
                    profile = SafetyProfile.ADVANCED,
                    egress = egress(endpoint = metadataEndpoint),
                    lanScopes = setOf(NetworkOriginScope("169.254.169.254", 80)),
                ),
            )
        assertEquals(PolicyDenialCode.RESERVED_ENDPOINT, denialOf(advanced).code)

        val standard = engine.evaluate(input(egress = egress(endpoint = metadataEndpoint)))
        assertEquals(PolicyDenialCode.RESERVED_ENDPOINT, denialOf(standard).code)
    }

    @Test
    fun customRemoteUnknownRaisesRiskToAtLeastL2() {
        val fromL0 = engine.evaluate(input(baseRisk = RiskLevel.L0, egress = egress(endpoint = unknownEndpoint)))
        assertEquals(RiskLevel.L2, fromL0.dynamicRisk)
        approvalOf(fromL0)

        val fromL1 = engine.evaluate(input(baseRisk = RiskLevel.L1, egress = egress(endpoint = unknownEndpoint)))
        assertEquals(RiskLevel.L2, fromL1.dynamicRisk)
    }

    @Test
    fun newOriginRaisesRiskOneLevel() {
        val fromL0 = engine.evaluate(input(baseRisk = RiskLevel.L0, egress = egress(), originSeenInSession = false))
        assertEquals(RiskLevel.L1, fromL0.dynamicRisk)
        assertTrue(fromL0.riskFactors.any { it.contains("new network origin") })

        val fromL1 = engine.evaluate(input(baseRisk = RiskLevel.L1, egress = egress(), originSeenInSession = false))
        assertEquals(RiskLevel.L2, fromL1.dynamicRisk)
    }

    @Test
    fun overwriteCodeChangeAndBindingChangeRaiseToAtLeastL2() {
        val overwrite = engine.evaluate(input(baseRisk = RiskLevel.L0, overwritesExisting = true))
        assertEquals(RiskLevel.L2, overwrite.dynamicRisk)

        val code = engine.evaluate(input(baseRisk = RiskLevel.L1, codeOrCommandChanged = true))
        assertEquals(RiskLevel.L2, code.dynamicRisk)

        val binding = engine.evaluate(input(baseRisk = RiskLevel.L1, sourceBindingChanged = true))
        assertEquals(RiskLevel.L2, binding.dynamicRisk)
    }

    @Test
    fun browserOriginFloorsLabeledNormalEgressToSensitive() {
        val standard =
            engine.evaluate(
                input(dataOrigin = DataOrigin.BROWSER, egress = egress(sensitivity = DataSensitivity.NORMAL)),
            )
        assertEquals(DataSensitivity.SENSITIVE, standard.effectiveDataCategory)
        approvalOf(standard)

        // under ADVANCED the floored SENSITIVE category can be covered by a SENSITIVE rule
        val advanced =
            engine.evaluate(
                input(
                    profile = SafetyProfile.ADVANCED,
                    dataOrigin = DataOrigin.BROWSER,
                    egress = egress(sensitivity = DataSensitivity.NORMAL),
                    scope = scope,
                ),
                setOf(rule()),
            )
        assertEquals(DataSensitivity.SENSITIVE, advanced.effectiveDataCategory)
        assertEquals(PolicyDecision.Allow, advanced.decision)
    }

    @Test
    fun engineIsStatelessAcrossRestart() {
        val base =
            input(
                profile = SafetyProfile.ADVANCED,
                egress = egress(sensitivity = DataSensitivity.SENSITIVE),
                scope = scope,
            )
        val live = rule()
        val first = PolicyEngine(FixedClock(now)).evaluate(base, setOf(live))
        val afterRestart = PolicyEngine(FixedClock(now)).evaluate(base, setOf(live))
        assertEquals(first, afterRestart)
    }
}

private class FixedClock(
    private val time: Instant,
) : Clock {
    override fun now(): Instant = time
}
