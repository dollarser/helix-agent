package com.helix.app.chat

import android.content.Context
import androidx.annotation.StringRes
import com.helix.app.R

/** Maps internal submission rejection reasons to localized strings while preserving gate messages. */
object ChatSubmissionErrorMapper {
    private val REASONS =
        mapOf(
            "ADMISSION_FAILED" to R.string.chat_submission_rejected_admission_failed,
            "SESSION_CHANGED" to R.string.chat_submission_rejected_session_changed,
            "CONFIRMATION_PENDING" to R.string.chat_submission_rejected_confirmation_pending,
            "INVALID_INPUT" to R.string.chat_submission_rejected_invalid_input,
            "PREPARING_DRAFT" to R.string.chat_submission_rejected_preparing_draft,
            "ATTACHMENTS_CHANGED" to R.string.chat_blocked_attachments_changed,
            "DRAFT_CHANGED" to R.string.chat_submission_rejected_draft_changed,
            "ATTACHMENT_PREPARATION_FAILED" to R.string.chat_submission_rejected_attachment_prep_failed,
            "REQUEST_ID_ALREADY_USED" to R.string.chat_submission_rejected_request_id_reused,
            "CONFIRMATION_CHANGED" to R.string.chat_submission_rejected_confirmation_changed,
            "NO_PROVIDER" to R.string.chat_blocked_no_provider_bound,
            "ATTACHMENT_VERIFICATION_FAILED" to R.string.chat_blocked_snapshot_verify_failed,
            "TURN_NOT_ACCEPTED" to R.string.chat_submission_rejected_turn_not_accepted,
        )

    @StringRes
    fun stringResFor(reason: String): Int? = REASONS[reason]

    fun mapReason(
        reason: String,
        context: Context,
    ): String? {
        if (reason == "USER_CANCELLED") return null
        val resId = stringResFor(reason)
        return if (resId != null) context.getString(resId) else reason
    }

    fun mapReason(
        reason: String,
        resolver: (Int) -> String,
    ): String? {
        if (reason == "USER_CANCELLED") return null
        val resId = stringResFor(reason)
        return if (resId != null) resolver(resId) else reason
    }
}
