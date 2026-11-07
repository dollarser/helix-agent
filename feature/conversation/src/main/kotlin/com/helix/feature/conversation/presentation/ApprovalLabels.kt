package com.helix.feature.conversation.presentation

import com.helix.core.model.SafetyProfile
import com.helix.feature.conversation.R

/** Safety Profile label — a string-resource ID (HXA-069). */
fun approvalProfileLabel(profile: SafetyProfile): Int =
    when (profile) {
        SafetyProfile.STANDARD -> R.string.approval_profile_standard
        SafetyProfile.ADVANCED -> R.string.approval_profile_advanced
    }

/** Card state label — a string-resource ID (HXA-069). */
fun approvalStateLabel(state: ApprovalCardState): Int =
    when (state) {
        ApprovalCardState.PENDING -> R.string.approval_state_pending
        ApprovalCardState.APPROVED -> R.string.approval_state_approved
        ApprovalCardState.DENIED -> R.string.approval_state_denied
        ApprovalCardState.SUCCEEDED -> R.string.approval_state_succeeded
        ApprovalCardState.FAILED -> R.string.approval_state_failed
    }
