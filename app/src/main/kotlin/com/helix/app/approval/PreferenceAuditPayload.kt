package com.helix.app.approval

import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope
import com.helix.core.policy.EffectiveToolPreference
import com.helix.core.policy.PolicyDecision
import com.helix.core.policy.ToolApprovalReason
import com.helix.core.policy.ToolApprovalResolution
import com.helix.tools.framework.PreferenceDecisionAudit
import java.security.MessageDigest

/** Versioned allowlist: no policy prose, tool arguments, external URLs or raw scope paths. */
data class PreferenceAuditPayload(
    val version: Int = 1,
    val effective: String,
    val source: ToolApprovalReason,
    val sourceHash: String,
    val contractHash: String,
    val policy: String,
    val policyDenial: String? = null,
    val resolution: String,
    val reason: ToolApprovalReason,
    val rules: List<PreferenceAuditRule>,
) {
    companion object {
        fun from(audit: PreferenceDecisionAudit): PreferenceAuditPayload {
            val effective = audit.preference.effective
            val resolution = audit.resolution
            return PreferenceAuditPayload(
                effective = effectiveName(effective),
                source = effectiveSource(effective),
                sourceHash = hash(audit.sourceRef),
                contractHash = audit.contractHash,
                policy =
                    when (audit.policy) {
                        PolicyDecision.Allow -> "ALLOW"
                        is PolicyDecision.Deny -> "DENY"
                        is PolicyDecision.RequiresApproval -> "REQUIRES_APPROVAL"
                    },
                policyDenial = (audit.policy as? PolicyDecision.Deny)?.code?.name,
                resolution =
                    when (resolution) {
                        is ToolApprovalResolution.AutoProceed -> "AUTO_PROCEED"
                        is ToolApprovalResolution.Blocked -> resolution.code.name
                        is ToolApprovalResolution.RequiresCard -> "REQUIRES_CARD"
                    },
                reason =
                    when (resolution) {
                        is ToolApprovalResolution.AutoProceed -> resolution.reason
                        is ToolApprovalResolution.Blocked -> resolution.reason
                        is ToolApprovalResolution.RequiresCard -> resolution.reason
                    },
                rules =
                    audit.preference.records.map {
                        PreferenceAuditRule(
                            it.id,
                            it.revision,
                            it.scope,
                            it.scopeRef?.let(::hash),
                            it.preference,
                            it.preference != ToolApprovalPreference.ALLOW || it.contractHash == audit.contractHash,
                        )
                    },
            )
        }

        private fun effectiveName(effective: EffectiveToolPreference): String =
            when (effective) {
                EffectiveToolPreference.Unset -> "UNSET"
                EffectiveToolPreference.Allow -> "ALLOW"
                EffectiveToolPreference.Deny -> "DENY"
                is EffectiveToolPreference.Ask -> "ASK"
            }

        private fun effectiveSource(effective: EffectiveToolPreference): ToolApprovalReason =
            when (effective) {
                EffectiveToolPreference.Unset -> ToolApprovalReason.UNSET
                is EffectiveToolPreference.Ask -> effective.reason
                else -> ToolApprovalReason.EXPLICIT
            }

        fun encode(audit: PreferenceDecisionAudit) = PreferenceAuditCodec.encode(from(audit))

        /** Old rows have no preference object; unknown versions never become inferred ALLOW. */
        fun decode(value: String): PreferenceAuditPayload? = PreferenceAuditCodec.decode(value)

        private fun hash(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

data class PreferenceAuditRule(
    val id: String?,
    val revision: Long?,
    val scope: ToolApprovalPreferenceScope,
    val scopeHash: String?,
    val storedPreference: ToolApprovalPreference,
    val contractValid: Boolean,
)
