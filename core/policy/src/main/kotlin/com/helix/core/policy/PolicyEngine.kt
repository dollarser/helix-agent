package com.helix.core.policy

import com.helix.core.model.AgentMode
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderResidence
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.isReviewModeAdmitted
import java.time.Instant

/** Stable denial reasons for UI, audit and tests (mirrors ModeDenialCode's style). */
enum class PolicyDenialCode {
    /** Ungranted capabilities are denied by default (roadmap HXA-033; HXA-032 center). */
    CAPABILITY_NOT_GRANTED,

    /** Credentials never egress, in either profile; no rule can release them. */
    CREDENTIALS_ALWAYS_DENIED,

    /** LAN/loopback egress under STANDARD, or without a matching exact NetworkOriginScope. */
    LAN_NOT_ALLOWED,

    /** Cloud metadata and platform-reserved endpoints are always rejected (SSRF). */
    RESERVED_ENDPOINT,

    /** Plan mode admits only READ_ONLY or METADATA; the class denial no risk level can substitute. */
    PLAN_MODE_NOT_READ_ONLY,

    /** Chat has no tools unless the user explicitly opts in for this Turn. */
    CHAT_TOOLS_DISABLED,

    /** Chat and Plan admit only READ_ONLY or closed METADATA operations. */
    MODE_OPERATION_DENIED,

    /** PRoot/CLI runtimes are ADVANCED-only (ADR-0005). */
    ISOLATED_RUNTIME_REQUIRES_ADVANCED,
}

/** Result of the per-call Policy Engine check (the approval stage consumes it). */
sealed interface PolicyDecision {
    /** The policy allows the call to proceed (approval still applies per [ToolOperationClass]). */
    data object Allow : PolicyDecision

    /** The policy denies the call outright; [code] is stable for audit and UI. */
    data class Deny(
        val code: PolicyDenialCode,
        val detail: String,
    ) : PolicyDecision

    /**
     * The call needs per-call user approval before it may proceed. [detail] carries the
     * human-readable confirmation surface (data category + Provider/MCP + origin + scope for
     * egress gates).
     */
    data class RequiresApproval(
        val detail: String,
    ) : PolicyDecision
}

/**
 * The engine's output: the registered operation, decision and auditable policy facts.
 */
data class PolicyEvaluation(
    val operationClass: ToolOperationClass,
    val decision: PolicyDecision,
    val policyFactors: List<String>,
    /** The egress category after the origin floor (null when the call does not egress). */
    val effectiveDataCategory: DataSensitivity?,
    /**
     * The LIVE, exactly-bound ADVANCED rule that satisfied this call's high-sensitivity
     * egress (null when the egress is per-call gated or the call does not egress). The
     * approval card must display it as a BOUNDED Policy rule with its window — never as a
     * general credential (roadmap HXA-036: 高敏出网规则单独标为有界 Policy 规则).
     */
    val matchedEgressRule: HighSensitivityRule? = null,
)

/**
 * The Policy Engine (architecture doc section 8; ADR-0005; roadmap HXA-033).
 *
 * Stateless by design: all inputs are per-call facts, rules are external state passed per call,
 * and the only ambient dependency is the [Clock] — a restart loses nothing and gains nothing.
 * Every stored rule is re-validated against the live clock and all binding fields on every call.
 *
 * ModePolicy (core:agent) consumes [PolicyEvaluation.operationClass] through ToolModeProfile
 * External annotations and Skill instructions never grant capability or authorization.
 *
 * One pipeline per check (default denials -> egress gate -> policy facts -> decision); the
 * small private helpers are stages of that single pipeline, not independent operations
 * (@Suppress("TooManyFunctions")).
 */
@Suppress("TooManyFunctions")
class PolicyEngine(
    private val clock: Clock,
) {
    fun evaluate(
        input: PolicyInput,
        rules: Set<HighSensitivityRule> = emptySet(),
    ): PolicyEvaluation {
        val factors = mutableListOf<String>()
        val defaultDenial = checkDefaultDenials(input)
        val gate =
            if (defaultDenial == null) {
                evaluateEgressGate(input, rules, clock.now(), factors)
            } else {
                EgressGate.none()
            }
        val modeDenial = if (defaultDenial == null && gate.denial == null) modeDenial(input) else null
        val decision =
            when {
                defaultDenial != null -> {
                    defaultDenial
                }

                gate.denial != null -> {
                    gate.denial
                }

                modeDenial != null -> {
                    modeDenial
                }

                gate.approvalDetail != null -> {
                    PolicyDecision.RequiresApproval(gate.approvalDetail)
                }

                !input.operationClass.isReviewModeAdmitted -> {
                    PolicyDecision.RequiresApproval("Operation ${input.operationClass} requires explicit authorization")
                }

                else -> {
                    PolicyDecision.Allow
                }
            }
        return PolicyEvaluation(input.operationClass, decision, factors, gate.category, gate.matchedRule)
    }

    private fun modeDenial(input: PolicyInput): PolicyDecision.Deny? =
        when {
            input.mode == AgentMode.CHAT && !input.chatToolsEnabled -> {
                deny(PolicyDenialCode.CHAT_TOOLS_DISABLED, "Chat tools were not enabled by the user")
            }

            input.mode == AgentMode.CHAT && !input.operationClass.isReviewModeAdmitted -> {
                deny(
                    PolicyDenialCode.MODE_OPERATION_DENIED,
                    "Chat mode permits reads and closed metadata operations only",
                )
            }

            else -> {
                null
            }
        }

    /** Capability, channel and review-mode boundaries precede authorization. */
    private fun checkDefaultDenials(input: PolicyInput): PolicyDecision.Deny? =
        when {
            input.missingCapabilities.isNotEmpty() -> {
                deny(
                    PolicyDenialCode.CAPABILITY_NOT_GRANTED,
                    "missing capabilities: ${input.missingCapabilities.sorted()}",
                )
            }

            input.mode == AgentMode.PLAN && !input.operationClass.isReviewModeAdmitted -> {
                deny(
                    PolicyDenialCode.PLAN_MODE_NOT_READ_ONLY,
                    "Plan mode allows only READ_ONLY or METADATA; " +
                        "operation class ${input.operationClass} is neither",
                )
            }

            input.executionTarget in ADVANCED_ONLY_TARGETS && input.profile == SafetyProfile.STANDARD -> {
                deny(
                    PolicyDenialCode.ISOLATED_RUNTIME_REQUIRES_ADVANCED,
                    "execution target ${input.executionTarget} requires the ADVANCED profile",
                )
            }

            else -> {
                null
            }
        }

    /**
     * The ADR-0005 egress gate: forbidden data is always denied, reserved endpoints always
     * denied, LAN/loopback only under ADVANCED with an exact user-created scope, and
     * high-sensitivity data only per-call or through a live exactly-bound rule.
     */
    private fun evaluateEgressGate(
        input: PolicyInput,
        rules: Set<HighSensitivityRule>,
        now: Instant,
        factors: MutableList<String>,
    ): EgressGate {
        val egress = input.egress
        val category = egress?.let { maxOf(it.dataSensitivity, input.dataOrigin.sensitivityFloor()) }
        val residence = egress?.endpoint?.residence()
        val lanScope = findMatchingLanScope(input, egress, residence)
        val denial = egressDenial(input, egress, category, residence, lanScope)

        if (egress != null && denial == null) {
            if (lanScope != null) factors += "LAN scope ${lanScope.matchKey} matches"
            if (residence == ProviderResidence.CUSTOM_REMOTE_UNKNOWN) {
                factors += "CUSTOM_REMOTE_UNKNOWN destination has no established data residence"
            }
        }

        val approval = egressApprovalDetail(input, rules, now, factors, egress, category, denial)
        return EgressGate(category, denial, approval.detail, approval.matchedRule)
    }

    /** The exact user-created LAN/loopback scope for this egress, if any (no wildcards). */
    private fun findMatchingLanScope(
        input: PolicyInput,
        egress: EgressRequest?,
        residence: ProviderResidence?,
    ): NetworkOriginScope? {
        val lan =
            residence == ProviderResidence.USER_AUTHORIZED_LAN ||
                residence == ProviderResidence.ON_DEVICE_LOOPBACK
        if (!lan || egress == null) return null
        return input.lanScopes.firstOrNull { scope ->
            scope.matches(egress.endpoint.host, egress.endpoint.port)
        }
    }

    /** The ADR-0005 egress denial (forbidden category, reserved endpoint, unscoped LAN). */
    private fun egressDenial(
        input: PolicyInput,
        egress: EgressRequest?,
        category: DataSensitivity?,
        residence: ProviderResidence?,
        lanScope: NetworkOriginScope?,
    ): PolicyDecision.Deny? =
        egress?.let { request ->
            val lan =
                residence == ProviderResidence.USER_AUTHORIZED_LAN ||
                    residence == ProviderResidence.ON_DEVICE_LOOPBACK
            when {
                category == DataSensitivity.FORBIDDEN -> {
                    deny(
                        PolicyDenialCode.CREDENTIALS_ALWAYS_DENIED,
                        "forbidden data category $category never egresses (no profile or rule can release it)",
                    )
                }

                isReservedEndpoint(request.endpoint) -> {
                    deny(
                        PolicyDenialCode.RESERVED_ENDPOINT,
                        "cloud metadata / platform-reserved endpoint ${request.endpoint.origin} is always rejected",
                    )
                }

                lan && input.profile == SafetyProfile.STANDARD -> {
                    deny(
                        PolicyDenialCode.LAN_NOT_ALLOWED,
                        "$residence egress is not allowed under STANDARD",
                    )
                }

                lan && lanScope == null -> {
                    deny(
                        PolicyDenialCode.LAN_NOT_ALLOWED,
                        "no exact NetworkOriginScope for ${request.endpoint.origin} (ADVANCED settings only)",
                    )
                }

                else -> {
                    null
                }
            }
        }

    /**
     * Per-call confirmation detail for high-sensitivity egress; the detail is null when an
     * ADVANCED rule covers the exact binding (recorded as a risk factor instead), and the
     * covering rule is surfaced so the approval card can show it as a BOUNDED rule.
     */
    private fun egressApprovalDetail(
        input: PolicyInput,
        rules: Set<HighSensitivityRule>,
        now: Instant,
        factors: MutableList<String>,
        egress: EgressRequest?,
        category: DataSensitivity?,
        denial: PolicyDecision.Deny?,
    ): EgressApproval {
        if (egress == null || denial != null || category != DataSensitivity.SENSITIVE) {
            return EgressApproval(null, null)
        }
        // Past the guard: egress is non-null and the category is SENSITIVE.
        val matchedRule =
            if (input.profile == SafetyProfile.ADVANCED) {
                rules.firstOrNull { rule -> rule.isLiveFor(egress, category, input.scope, now) }
            } else {
                null
            }
        if (matchedRule != null) {
            factors += "ADVANCED high-sensitivity rule active (exact binding, live window)"
        }
        return if (matchedRule != null) {
            EgressApproval(null, matchedRule)
        } else {
            EgressApproval(
                "per-call confirmation: $category data to ${egress.target} " +
                    "at ${egress.endpoint.origin} " +
                    "(scope: ${input.scope?.toScopeRef() ?: "none"}) — no stored rule covers this exact binding",
                null,
            )
        }
    }

    /**
     * A rule covers a call only when every binding field matches exactly AND the rule is live
     * at [now]: `createdAt <= now < expiresAt` (clock rollback fails closed like expiry).
     */
    private fun HighSensitivityRule.isLiveFor(
        egress: EgressRequest,
        category: DataSensitivity,
        scope: UserScope?,
        now: Instant,
    ): Boolean =
        dataCategory == DataSensitivity.SENSITIVE &&
            category == DataSensitivity.SENSITIVE &&
            target == egress.target &&
            origin.full == egress.endpoint.full &&
            scope != null &&
            this.scope == scope &&
            !now.isBefore(createdAt) &&
            now.isBefore(expiresAt)

    private fun deny(
        code: PolicyDenialCode,
        detail: String,
    ): PolicyDecision.Deny = PolicyDecision.Deny(code, detail)

    /** Result of the egress gate; [category] is null when the call does not egress. */
    private data class EgressGate(
        val category: DataSensitivity?,
        val denial: PolicyDecision.Deny?,
        val approvalDetail: String?,
        val matchedRule: HighSensitivityRule?,
    ) {
        companion object {
            fun none(): EgressGate = EgressGate(null, null, null, null)
        }
    }

    /** The egress gate's approval surface: the per-call detail and the rule that covers it. */
    private data class EgressApproval(
        val detail: String?,
        val matchedRule: HighSensitivityRule?,
    )

    companion object {
        /** PRoot and CLI runtimes are Advanced-only (ADR-0005). QuickJS is a P0 tool and profile-independent. */
        private val ADVANCED_ONLY_TARGETS =
            setOf(
                ExecutionTargetType.LOCAL_PROOT,
                ExecutionTargetType.LOCAL_CLI_RUNTIME,
                ExecutionTargetType.LOCAL_ROOT,
            )

        /**
         * Cloud metadata and platform-reserved endpoints (SSRF policy). The full connection-time
         * SSRF policy (A/AAAA, IPv4-mapped IPv6, DNS rebinding, redirect revalidation,
         * connect-time revalidation) is enforced by the network layer (security doc 7.9); this
         * set is the decision-time floor.
         */
        private val RESERVED_HOSTS = setOf("169.254.169.254", "fd00:ec2::254", "100.100.100.200")

        internal fun isReservedEndpoint(endpoint: NormalizedEndpoint): Boolean = RESERVED_HOSTS.contains(endpoint.host)
    }
}
