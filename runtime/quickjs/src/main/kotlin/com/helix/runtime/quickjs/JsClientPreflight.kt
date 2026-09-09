package com.helix.runtime.quickjs

import java.nio.charset.StandardCharsets

internal object JsClientPreflight {
    /**
     * Pre-flight rejections (doc 03 §4.1): everything the client can decide BEFORE
     * binding is rejected here, so a rejected execution never spawns an isolated
     * process. Returns null when the execution may proceed.
     */
    fun preflightReject(
        params: JsExecuteParams,
        cancellation: JsCancellation?,
        inputSha: String,
    ): JsExecutionResult? {
        val limitsError: String? =
            try {
                params.limits.validate()
                null
            } catch (e: IllegalArgumentException) {
                "invalid limits: ${e.message}"
            }
        val inputError = preflightInputReject(params.inputJsonUtf8)
        val sizeError = preflightSizeReject(params, params.limits)
        return when {
            cancellation?.isCancelled() == true -> {
                rejection(params, JsExecutionStatus.CANCELLED, "cancelled before start", inputSha)
            }

            params.executionId.isBlank() -> {
                rejection(params, JsExecutionStatus.REQUEST_REJECTED, "blank executionId", inputSha)
            }

            limitsError != null -> {
                rejection(params, JsExecutionStatus.REQUEST_REJECTED, limitsError, inputSha)
            }

            inputError != null -> {
                rejection(params, JsExecutionStatus.REQUEST_REJECTED, inputError, inputSha)
            }

            sizeError != null -> {
                rejection(params, JsExecutionStatus.REQUEST_REJECTED, sizeError, inputSha)
            }

            params.debugInjectCrash && !BuildConfig.DEBUG -> {
                rejection(
                    params,
                    JsExecutionStatus.REQUEST_REJECTED,
                    "crash-injection seam is disabled outside debug builds",
                    inputSha,
                )
            }

            else -> {
                null
            }
        }
    }

    /** HXA-052: a non-empty input must be exactly one valid JSON document (doc 03 §3.2). */
    private fun preflightInputReject(inputJsonUtf8: ByteArray?): String? {
        if (inputJsonUtf8 == null || inputJsonUtf8.isEmpty()) return null
        return if (JsJsonDocument.isValidJson(inputJsonUtf8)) {
            null
        } else {
            "input is not a valid JSON document"
        }
    }

    private fun preflightSizeReject(
        params: JsExecuteParams,
        limits: JsExecutionLimits,
    ): String? {
        val inputBytes = params.inputJsonUtf8
        return when {
            params.source.toByteArray(StandardCharsets.UTF_8).size > limits.maxSourceBytes -> {
                "source exceeds maxSourceBytes ${limits.maxSourceBytes}"
            }

            inputBytes != null && inputBytes.size > limits.maxInputBytes -> {
                "input ${inputBytes.size} exceeds maxInputBytes ${limits.maxInputBytes}"
            }

            else -> {
                null
            }
        }
    }

    private fun rejection(
        params: JsExecuteParams,
        status: JsExecutionStatus,
        detail: String,
        inputSha: String,
    ): JsExecutionResult = JsExecutionResult.clientFailure(params.executionId, status, detail, inputSha)
}
