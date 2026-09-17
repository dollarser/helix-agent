package com.helix.tools.framework

import com.helix.core.policy.OperationFootprint
import com.helix.core.policy.PermissionReason
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.policy.SessionPermissionResolution

/**
 * The platform-owned effect classification of one validated ToolCall (HXA-209 B3,
 * ADR-PERMISSIONS-001 section 2 step 4). Produced AFTER the registered-contract validation and
 * BEFORE the session permission resolver, exclusively from the registered descriptor, the raw
 * arguments and the trusted request facts.
 *
 * The model, an MCP `readOnlyHint` annotation, a tool name and the model-visible description can
 * never prove the absence of an effect (ADR section 1.2): an unproven effect is reported as
 * [OperationFootprint.undeterminedEffects], never dropped.
 *
 * [rmCommandHit] is the explicit `rm -rf` command rule (ADR section 3): the Shell entry point
 * feeds it from the exact argv/script it is about to run, and it is a floor that requires a
 * precise one-time approval in EVERY session mode, including FULL_ACCESS. It is a separate
 * reminder layer, not an [OperationEffect].
 */
data class CallEffectClassification(
    val footprint: OperationFootprint,
    val rmCommandHit: Boolean = false,
) {
    init {
        require(footprint.effects.intersect(footprint.undeterminedEffects).isEmpty()) {
            "an effect cannot be both determined and undetermined"
        }
    }
}

/**
 * The effect-classification seam the dispatcher consumes (HXA-209 B3). ONE implementation is
 * wired by the app for ALL execution lanes — the classifier, not the lane, is the single
 * decision seat's input (execution-domain matrix section 5: 不建立四条执行链). Implementations
 * are platform code: they read the arguments but never declare safety — a missing effect keeps
 * the call conservative (ASK / denied-when-DENY, never a silent pass).
 */
fun interface ToolEffectClassifier {
    fun classify(
        request: ToolDispatchRequest,
        descriptor: ToolDescriptor,
    ): CallEffectClassification
}

/**
 * The stored audit content of one session-permission decision (HXA-209 B3, ADR section 5:
 * per-invocation mode version, decision, canonical targets and reasons). Primitives only —
 * no policy prose, tool arguments, scope paths or output bodies enter the audit row; the
 * dispatcher's [DispatchAuditEvent] carries at most one evaluation and the at-start recheck.
 */
data class SessionPermissionDecisionAudit(
    val version: Int = 1,
    val mode: String,
    val configVersion: Int,
    val effects: List<String>,
    val undeterminedEffects: List<String>,
    val rmCommandHit: Boolean,
    val outcome: String,
    val denyCode: String? = null,
    val reasons: List<String> = emptyList(),
) {
    companion object {
        const val OUTCOME_AUTO_PROCEED = "AUTO_PROCEED"
        const val OUTCOME_REQUIRES_APPROVAL = "REQUIRES_APPROVAL"
        const val OUTCOME_DENIED = "DENIED"

        /** Deterministic (sorted) so the same decision always yields the same audit row. */
        fun from(
            config: SessionPermissionConfig,
            classification: CallEffectClassification,
            resolution: SessionPermissionResolution,
        ): SessionPermissionDecisionAudit =
            when (resolution) {
                is SessionPermissionResolution.AutoProceed -> {
                    SessionPermissionDecisionAudit(
                        mode = config.mode.name,
                        configVersion = config.configVersion,
                        effects =
                            classification.footprint.effects
                                .map { it.name }
                                .sorted(),
                        undeterminedEffects =
                            classification.footprint.undeterminedEffects
                                .map { it.name }
                                .sorted(),
                        rmCommandHit = classification.rmCommandHit,
                        outcome = OUTCOME_AUTO_PROCEED,
                    )
                }

                is SessionPermissionResolution.RequiresApproval -> {
                    SessionPermissionDecisionAudit(
                        mode = config.mode.name,
                        configVersion = config.configVersion,
                        effects =
                            classification.footprint.effects
                                .map { it.name }
                                .sorted(),
                        undeterminedEffects =
                            classification.footprint.undeterminedEffects
                                .map { it.name }
                                .sorted(),
                        rmCommandHit = classification.rmCommandHit,
                        outcome = OUTCOME_REQUIRES_APPROVAL,
                        reasons = resolution.reasons.map { it.reasonToken() }.sorted(),
                    )
                }

                is SessionPermissionResolution.Denied -> {
                    SessionPermissionDecisionAudit(
                        mode = config.mode.name,
                        configVersion = config.configVersion,
                        effects =
                            classification.footprint.effects
                                .map { it.name }
                                .sorted(),
                        undeterminedEffects =
                            classification.footprint.undeterminedEffects
                                .map { it.name }
                                .sorted(),
                        rmCommandHit = classification.rmCommandHit,
                        outcome = OUTCOME_DENIED,
                        denyCode = resolution.code.name,
                        reasons = resolution.reasons.map { it.reasonToken() }.sorted(),
                    )
                }
            }
    }
}

/** The stable audit token of one approval reason: `CODE` or `CODE:EFFECT`. */
internal fun PermissionReason.reasonToken(): String = effect?.let { "${code.name}:${it.name}" } ?: code.name

/** The model-free human detail of a reason set (card text / denial text). */
internal fun Collection<PermissionReason>.describe(): String = joinToString("; ") { it.reasonToken() }
