package com.helix.app.provider

import com.helix.app.R
import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.ProviderCapabilities

/**
 * The persisted, user-visible connection-test state of one provider (HXA-028).
 *
 * The three-way distinction is a task contract: a provider whose connection
 * test has not completed (or was overridden manually) must NOT be displayed as
 * "available" — only [State.Passed] (a completed [com.helix.provider.api.CapabilityProbe]
 * run) marks a provider usable for chat. [State.Failed] carries the probe
 * phase that failed so the UI can show WHERE the test stopped (FR-LLM-004:
 * distinguish network/TLS/auth, model list, text stream and tool call) with a
 * SAFE label — never a raw exception message (doc 02 section 13).
 *
 * HXA-069: [Failed.code] holds the STABLE [ModelErrorCode] (never locale text) and the chip
 * labels are string-resource IDs emitted by [ConnectionTestMapping] — the Compose UI resolves
 * them to the current locale, so this model stays pure-JVM-testable with no Context.
 */
sealed interface ConnectionTestStatus {
    /** No completed test yet — the provider cannot be selected for chat. */
    data object Untested : ConnectionTestStatus

    /** A completed probe; [capabilities] is the PROBED snapshot (source = PROBED). */
    data class Passed(
        val atMillis: Long,
        val capabilities: ProviderCapabilities,
    ) : ConnectionTestStatus

    /**
     * The probe stopped at [phase] (1 = transport/auth, 2 = model list,
     * 3 = minimal text stream, 4 = minimal tool call). [code] is the STABLE
     * [ModelErrorCode] (resolved to a safe localized label by the UI — never a
     * raw exception message); [retryable] mirrors the probe's own
     * classification (network-class failures are retryable).
     */
    data class Failed(
        val atMillis: Long,
        val phase: Int,
        val code: ModelErrorCode,
        val retryable: Boolean,
    ) : ConnectionTestStatus
}

/** Maps a probe outcome to [ConnectionTestStatus] (pure; unit-tested). */
object ConnectionTestMapping {
    /**
     * The string-resource id of the SAFE user-visible label for [code]
     * (doc 02 section 13: no raw exception text; resolved to the current
     * locale by the UI, HXA-069).
     */
    fun codeLabel(code: ModelErrorCode): Int =
        when (code) {
            ModelErrorCode.TRANSPORT -> R.string.conn_error_transport
            ModelErrorCode.TIMEOUT -> R.string.conn_error_timeout
            ModelErrorCode.AUTH -> R.string.conn_error_auth
            ModelErrorCode.RATE_LIMITED -> R.string.conn_error_rate_limited
            ModelErrorCode.SERVER_ERROR -> R.string.conn_error_server
            ModelErrorCode.HTTP_ERROR -> R.string.conn_error_http
            ModelErrorCode.PROTOCOL -> R.string.conn_error_protocol
            ModelErrorCode.CONTENT_FILTER -> R.string.conn_error_content_filter
        }

    /**
     * The string-resource id of the four probe phases (HXA-025 CapabilityProbe,
     * FR-LLM-004 display order; resolved to the current locale by the UI).
     */
    fun phaseLabel(phase: Int): Int =
        when (phase) {
            1 -> R.string.conn_phase_1
            2 -> R.string.conn_phase_2
            3 -> R.string.conn_phase_3
            4 -> R.string.conn_phase_4
            else -> R.string.conn_phase_unknown
        }
}
