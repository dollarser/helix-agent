package com.helix.app.chat

import com.helix.app.R
import com.helix.app.runcontrol.RunControlConfig
import com.helix.app.runcontrol.RunControlStore
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.TurnState

/** Stable protocol/error facts mapped to localized presentation at display time. */
internal class ChatStatusLabels(
    private val strings: (Int, Array<out Any>) -> String,
) {
    private fun str(id: Int): String = strings(id, emptyArray())

    /**
     * The localized label for a terminal turn, or null when the terminal carries none (a clean
     * COMPLETED). CANCELLED is the fixed stop label; FAILED resolves [errorCode] via
     * [modelTerminalCodeRes].
     */
    fun terminalLabel(
        state: TurnState,
        errorCode: String?,
    ): String? =
        when (state) {
            TurnState.FAILED -> str(modelTerminalCodeRes(errorCode))
            TurnState.CANCELLED -> str(R.string.turn_stopped)
            else -> null
        }

    /** Maps a terminal turn's stable error code to its user-visible string-resource id. */
    @Suppress("CyclomaticComplexMethod") // closed error-code to resource mapping
    fun modelTerminalCodeRes(errorCode: String?): Int =
        when (errorCode) {
            "CONTEXT_NOT_COMPACTABLE", "CONTEXT_NO_GAIN" -> R.string.context_not_compactable
            "CONTEXT_WINDOW_LIMIT" -> R.string.context_window_limit
            "CONTEXT_SUMMARY_INVALID" -> R.string.context_summary_invalid
            ModelStreamState.REFUSAL -> R.string.model_refused
            ModelStreamState.TOOL_STREAM_TRUNCATED -> R.string.model_error_tool_stream_truncated
            ModelStreamState.TOOL_STREAM_INVALID -> R.string.model_error_tool_stream_invalid
            ModelStreamState.TOOL_ARGUMENTS_OVERFLOW -> R.string.model_error_tool_args_overflow
            ModelStreamState.TOOL_CALL_COUNT_OVERFLOW -> R.string.model_error_tool_call_count_overflow
            ModelStreamState.MODEL_TEXT_OVERFLOW -> R.string.model_error_model_text_overflow
            "TOOL_STEP_LIMIT" -> R.string.model_error_tool_step_limit
            "MODEL_CALL_LIMIT" -> R.string.model_error_model_call_limit
            "TOKEN_BUDGET_LIMIT" -> R.string.model_error_token_budget_limit
            "GOAL_BUDGET_LIMIT" -> R.string.model_error_goal_budget_limit
            "GOAL_TIME_WINDOW_EXPIRED" -> R.string.goal_time_window_expired
            null -> R.string.model_error_generic
            else -> modelErrorCodeLabelRes(errorCode)
        }

    /** Maps a persisted provider [ModelErrorCode] name to its string-resource id (fail-closed). */
    @Suppress("SwallowedException") // unknown code: the conservative generic label IS the handling
    private fun modelErrorCodeLabelRes(code: String): Int =
        try {
            when (ModelErrorCode.valueOf(code)) {
                ModelErrorCode.TRANSPORT -> R.string.conn_error_transport
                ModelErrorCode.TIMEOUT -> R.string.conn_error_timeout
                ModelErrorCode.AUTH -> R.string.conn_error_auth
                ModelErrorCode.RATE_LIMITED -> R.string.conn_error_rate_limited
                ModelErrorCode.SERVER_ERROR -> R.string.conn_error_server
                ModelErrorCode.HTTP_ERROR -> R.string.conn_error_http
                ModelErrorCode.PROTOCOL -> R.string.conn_error_protocol
                ModelErrorCode.CONTENT_FILTER -> R.string.conn_error_content_filter
            }
        } catch (e: IllegalArgumentException) {
            R.string.model_error_generic
        }

    /** The localized label for an egress rejection's stable code (never the matched content). */
    fun egressRejectedLabel(code: String): String =
        if (code == ForbiddenContentGuard.CREDENTIAL_DETECTED) {
            str(R.string.egress_credential_rejected)
        } else {
            str(R.string.model_error_generic)
        }
}
