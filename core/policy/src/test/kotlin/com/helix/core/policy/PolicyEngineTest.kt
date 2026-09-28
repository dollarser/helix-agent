package com.helix.core.policy

import com.helix.core.model.AgentMode
import com.helix.core.model.Capability
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.McpServerId
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderId
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
        operationClass: ToolOperationClass = ToolOperationClass.NETWORK,
        mode: AgentMode = AgentMode.ACT,
        chatToolsEnabled: Boolean = false,
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
        operationClass = operationClass,
        mode = mode,
        chatToolsEnabled = chatToolsEnabled,
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
        val evaluation = engine.evaluate(input(operationClass = ToolOperationClass.READ_ONLY))
        assertEquals(PolicyDecision.Allow, evaluation.decision)
        assertEquals(ToolOperationClass.READ_ONLY, evaluation.operationClass)
        assertTrue(evaluation.policyFactors.isEmpty())
    }

    @Test
    fun mutationsAndPrivilegedOperationsRequireAuthorizationWithoutAnOrdinalDefaultDeny() {
        for (operation in listOf(ToolOperationClass.LOCAL_MUTATION, ToolOperationClass.PRIVILEGED)) {
            val result = engine.evaluate(input(operationClass = operation))
            approvalOf(result)
            assertEquals(operation, result.operationClass)
        }
    }

    @Test
    fun parameterChangesNeverReclassifyTheTrustedOperation() {
        for (operation in ToolOperationClass.entries) {
            for (changed in listOf(false, true)) {
                val result =
                    engine.evaluate(
                        input(
                            operationClass = operation,
                            overwritesExisting = changed,
                            codeOrCommandChanged = changed,
                            sourceBindingChanged = changed,
                            originSeenInSession = !changed,
                        ),
                    )
                assertEquals(operation, result.operationClass)
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
                input(mode = AgentMode.PLAN, operationClass = ToolOperationClass.READ_ONLY),
            )
        assertEquals(PolicyDecision.Allow, evaluation.decision)
    }

    @Test
    fun planModeMetadataAtLowRiskIsAllowed() {
        // plan.submit is a METADATA op: admitted in Plan at the same cap as READ_ONLY, not a
        // disguised read.
        val evaluation =
            engine.evaluate(
                input(mode = AgentMode.PLAN, operationClass = ToolOperationClass.METADATA),
            )
        assertEquals(PolicyDecision.Allow, evaluation.decision)
    }

    @Test
    fun planMetadataIsAllowedWithoutAnOrdinalCeiling() {
        assertEquals(
            PolicyDecision.Allow,
            engine
                .evaluate(
                    input(
                        mode = AgentMode.PLAN,
                        operationClass = ToolOperationClass.METADATA,
                        sourceBindingChanged = true,
                    ),
                ).decision,
        )
    }

    @Test
    fun chatRequiresOptInAndRejectsAllEffectfulClasses() {
        assertEquals(
            PolicyDenialCode.CHAT_TOOLS_DISABLED,
            denialOf(engine.evaluate(input(mode = AgentMode.CHAT, operationClass = ToolOperationClass.READ_ONLY))).code,
        )
        for (operation in ToolOperationClass.entries) {
            val result =
                engine.evaluate(
                    input(mode = AgentMode.CHAT, chatToolsEnabled = true, operationClass = operation),
                )
            if (operation in setOf(ToolOperationClass.READ_ONLY, ToolOperationClass.METADATA)) {
                assertEquals(PolicyDecision.Allow, result.decision)
            } else {
                assertEquals(PolicyDenialCode.MODE_OPERATION_DENIED, denialOf(result).code)
            }
        }
    }

    @Test
    fun chatMetadataStillCannotExportCredentials() {
        val result =
            engine.evaluate(
                input(
                    mode = AgentMode.CHAT,
                    chatToolsEnabled = true,
                    operationClass = ToolOperationClass.METADATA,
                    egress = egress(sensitivity = DataSensitivity.FORBIDDEN),
                ),
            )
        assertEquals(PolicyDenialCode.CREDENTIALS_ALWAYS_DENIED, denialOf(result).code)
    }

    @Test
    fun planReadStillChecksEgressInsteadOfAnOrdinalCeiling() {
        val result =
            engine.evaluate(
                input(
                    mode = AgentMode.PLAN,
                    operationClass = ToolOperationClass.READ_ONLY,
                    egress = egress(sensitivity = DataSensitivity.FORBIDDEN),
                ),
            )
        assertEquals(PolicyDenialCode.CREDENTIALS_ALWAYS_DENIED, denialOf(result).code)
    }

    // --- goal mode: where a plan-executing goal runs -------------------------------
    // A goal created by executing an approved plan runs its turns in GOAL mode, NOT the
    // restricted PLAN mode — so it can perform the mutations the plan specifies. But the
    // plan's approval is NOT a tool approval: every high-risk call still goes through the
    // per-call gate (research doc 5.1: 后续写入、删除、外发仍重新经过既有授权判断 / 高风险调用
    // 仍需精确审批).

    @Test
    fun goalModePerformsTheMutationsTheApprovedPlanSpecifies() {
        // The same LOCAL_MUTATION that PLAN mode denies by operation class is admissible in
        // GOAL mode: executing a plan is not stuck in the read-only review class.
        val plan =
            engine.evaluate(
                input(
                    mode = AgentMode.PLAN,
                    operationClass = ToolOperationClass.LOCAL_MUTATION,
                ),
            )
        assertEquals(PolicyDenialCode.PLAN_MODE_NOT_READ_ONLY, denialOf(plan).code)

        val goal =
            engine.evaluate(
                input(
                    mode = AgentMode.GOAL,
                    operationClass = ToolOperationClass.LOCAL_MUTATION,
                ),
            )
        approvalOf(goal)
    }

    @Test
    fun goalModeHighRiskCallStillRequiresPerCallApproval() {
        // Approving/executing the plan minted no tool approval: an L2 mutation in GOAL mode is
        // still gated per call, not waved through because a plan was approved.
        val evaluation =
            engine.evaluate(
                input(
                    mode = AgentMode.GOAL,
                    operationClass = ToolOperationClass.LOCAL_MUTATION,
                ),
            )
        approvalOf(evaluation)
        assertEquals(ToolOperationClass.LOCAL_MUTATION, evaluation.operationClass)
    }

    @Test
    fun goalPrivilegedActionNeedsAuthorizationRegardlessOfPlan() {
        approvalOf(engine.evaluate(input(mode = AgentMode.GOAL, operationClass = ToolOperationClass.PRIVILEGED)))
    }

    @Test
    fun advancedOnlyRuntimesAreDeniedUnderStandard() {
        val proot =
            engine.evaluate(input(executionTarget = ExecutionTargetType.LOCAL_PROOT))
        assertEquals(PolicyDenialCode.ISOLATED_RUNTIME_REQUIRES_ADVANCED, denialOf(proot).code)

        val cli = engine.evaluate(input(executionTarget = ExecutionTargetType.LOCAL_CLI_RUNTIME))
        assertEquals(PolicyDenialCode.ISOLATED_RUNTIME_REQUIRES_ADVANCED, denialOf(cli).code)

        val root = engine.evaluate(input(executionTarget = ExecutionTargetType.LOCAL_ROOT))
        assertEquals(PolicyDenialCode.ISOLATED_RUNTIME_REQUIRES_ADVANCED, denialOf(root).code)
    }

    @Test
    fun advancedOnlyRuntimesAreAllowedUnderAdvanced() {
        val evaluation =
            engine.evaluate(
                input(
                    operationClass = ToolOperationClass.READ_ONLY,
                    profile = SafetyProfile.ADVANCED,
                    executionTarget = ExecutionTargetType.LOCAL_PROOT,
                ),
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
                    operationClass = ToolOperationClass.READ_ONLY,
                    profile = SafetyProfile.ADVANCED,
                    egress = egress(sensitivity = DataSensitivity.SENSITIVE),
                    scope = scope,
                ),
                setOf(live),
            )
        assertEquals(PolicyDecision.Allow, evaluation.decision)
        assertTrue(evaluation.policyFactors.any { it.contains("ADVANCED high-sensitivity rule active") })
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
                operationClass = ToolOperationClass.READ_ONLY,
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
        val without =
            engine.evaluate(
                input(
                    operationClass = ToolOperationClass.READ_ONLY,
                    profile = SafetyProfile.ADVANCED,
                    egress = egress(endpoint = lanEndpoint),
                ),
            )
        assertEquals(PolicyDenialCode.LAN_NOT_ALLOWED, denialOf(without).code)

        val wrongPort =
            engine.evaluate(
                input(
                    operationClass = ToolOperationClass.READ_ONLY,
                    profile = SafetyProfile.ADVANCED,
                    egress = egress(endpoint = lanEndpoint),
                    lanScopes = setOf(NetworkOriginScope("192.168.1.10", 9999)),
                ),
            )
        assertEquals(PolicyDenialCode.LAN_NOT_ALLOWED, denialOf(wrongPort).code)

        val exact =
            engine.evaluate(
                input(
                    operationClass = ToolOperationClass.READ_ONLY,
                    profile = SafetyProfile.ADVANCED,
                    egress = egress(endpoint = lanEndpoint),
                    lanScopes = setOf(NetworkOriginScope("192.168.1.10", 11434)),
                ),
            )
        assertEquals(PolicyDecision.Allow, exact.decision)
        assertTrue(exact.policyFactors.any { it.contains("192.168.1.10:11434") })
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
    fun unknownRemoteKeepsNetworkClassificationAndNeedsAuthorization() {
        val result = engine.evaluate(input(egress = egress(endpoint = unknownEndpoint)))
        assertEquals(ToolOperationClass.NETWORK, result.operationClass)
        approvalOf(result)
    }

    @Test
    fun newOriginDoesNotInventAnotherOperationClass() {
        val result = engine.evaluate(input(egress = egress(), originSeenInSession = false))
        assertEquals(ToolOperationClass.NETWORK, result.operationClass)
        approvalOf(result)
    }

    @Test
    fun changedMutationsRemainSubjectToAuthorization() {
        approvalOf(
            engine.evaluate(input(operationClass = ToolOperationClass.LOCAL_MUTATION, overwritesExisting = true)),
        )
        approvalOf(
            engine.evaluate(input(operationClass = ToolOperationClass.CODE_EXECUTION, codeOrCommandChanged = true)),
        )
        approvalOf(engine.evaluate(input(operationClass = ToolOperationClass.NETWORK, sourceBindingChanged = true)))
    }

    @Test
    fun browserOriginFloorsLabeledNormalEgressToSensitive() {
        val standard =
            engine.evaluate(
                input(
                    operationClass = ToolOperationClass.READ_ONLY,
                    dataOrigin = DataOrigin.BROWSER,
                    egress = egress(sensitivity = DataSensitivity.NORMAL),
                ),
            )
        assertEquals(DataSensitivity.SENSITIVE, standard.effectiveDataCategory)
        approvalOf(standard)

        // under ADVANCED the floored SENSITIVE category can be covered by a SENSITIVE rule
        val advanced =
            engine.evaluate(
                input(
                    operationClass = ToolOperationClass.READ_ONLY,
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
