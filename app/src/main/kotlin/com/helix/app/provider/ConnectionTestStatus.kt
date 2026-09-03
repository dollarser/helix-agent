package com.helix.app.provider

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
 */
sealed interface ConnectionTestStatus {
    /** No completed test yet — the provider cannot be selected for chat. */
    data object Untested : ConnectionTestStatus

    /**
     * A completed probe; [capabilities] is the PROBED snapshot (source = PROBED).
     * [modelIds] (HXA-059) is the backend's model list carried out of the phase-2
     * query — `null` when the backend does not expose a list (the UI then shows
     * "the backend gives no model list, enter it manually"). The list is display
     * data only: it never enters logs/diagnostics and the model field stays
     * user-editable (selecting an id prefills the edit form; it is NOT auto-saved).
     */
    data class Passed(
        val atMillis: Long,
        val capabilities: ProviderCapabilities,
        val modelIds: List<String>? = null,
    ) : ConnectionTestStatus

    /**
     * The probe stopped at [phase] (1 = transport/auth, 2 = model list,
     * 3 = minimal text stream, 4 = minimal tool call). [codeLabel] is the
     * safe Chinese label of the [ModelErrorCode]; [retryable] mirrors the
     * probe's own classification (network-class failures are retryable).
     */
    data class Failed(
        val atMillis: Long,
        val phase: Int,
        val codeLabel: String,
        val retryable: Boolean,
    ) : ConnectionTestStatus

    /** The status chip text shown on the provider row. */
    fun chipText(): String =
        when (this) {
            Untested -> "未测试"
            is Passed -> "已通过 · 能力已探测"
            is Failed -> "测试未通过（${ConnectionTestMapping.phaseLabel(phase)}）"
        }
}

/** Maps a probe outcome to [ConnectionTestStatus] (pure; unit-tested). */
object ConnectionTestMapping {
    /**
     * [code] is the [ModelErrorCode] the probe reported; it is converted to a
     * SAFE user-visible label (doc 02 section 13: no raw exception text).
     */
    fun codeLabel(code: ModelErrorCode): String =
        when (code) {
            ModelErrorCode.TRANSPORT -> "网络/TLS 连接失败"
            ModelErrorCode.TIMEOUT -> "连接或响应超时"
            ModelErrorCode.AUTH -> "认证失败（key 缺失或无效）"
            ModelErrorCode.RATE_LIMITED -> "服务限流（稍后重试）"
            ModelErrorCode.SERVER_ERROR -> "服务端错误"
            ModelErrorCode.HTTP_ERROR -> "HTTP 错误（端点或协议不匹配）"
            ModelErrorCode.PROTOCOL -> "协议响应不符合预期"
            ModelErrorCode.CONTENT_FILTER -> "被服务端内容过滤拒绝"
        }

    /** The four probe phases (HXA-025 CapabilityProbe, FR-LLM-004 display order). */
    fun phaseLabel(phase: Int): String =
        when (phase) {
            1 -> "网络与认证"
            2 -> "模型列表"
            3 -> "最小文本流"
            4 -> "最小工具调用"
            else -> "未知阶段"
        }
}
