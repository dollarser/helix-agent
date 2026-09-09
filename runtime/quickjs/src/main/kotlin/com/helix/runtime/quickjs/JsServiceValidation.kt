package com.helix.runtime.quickjs

internal object JsServiceValidation {
    /**
     * Validates the request+envelope pair BEFORE any engine work (doc 03 §4.1 limits
     * are enforced here as well as pre-bind on the client). Returns a stable rejection
     * reason, or null when the request is executable.
     */
    @Suppress("ReturnCount") // one return per distinct rejection reason
    fun validateRequest(
        request: JsExecutionRequest,
        envelope: JsExecutionWire.ExecuteEnvelope,
    ): String? {
        if (request.executionId.isBlank()) return "blank executionId"
        try {
            request.limits.validate()
        } catch (e: IllegalArgumentException) {
            return "invalid limits: ${e.message}"
        }
        if (request.deadlineNanos <= System.nanoTime()) return "deadline already expired"
        if ((envelope.flags and JsProtocol.FLAG_CRASH_INJECTION.inv()) != 0) return "unknown EXECUTE flags"
        return validateSource(request, envelope) ?: validateInput(request, envelope)
    }

    /** PFD/inline consistency and size caps for the source payload. */
    @Suppress("ReturnCount") // one return per distinct rejection reason
    private fun validateSource(
        request: JsExecutionRequest,
        envelope: JsExecutionWire.ExecuteEnvelope,
    ): String? {
        val limits = request.limits
        val inlineSource = request.sourceUtf8
        if (envelope.sourcePfd != null) {
            if (inlineSource.isNotEmpty()) return "source must not be inline when a source PFD is provided"
            if (envelope.sourceTotalBytes < 0) return "negative source length"
            if (envelope.sourceTotalBytes > limits.maxSourceBytes) {
                return "source ${envelope.sourceTotalBytes} exceeds maxSourceBytes ${limits.maxSourceBytes}"
            }
            return null
        }
        if (envelope.sourceTotalBytes != inlineSource.size) {
            return "source length mismatch (declared ${envelope.sourceTotalBytes}, inline ${inlineSource.size})"
        }
        if (inlineSource.size > limits.maxSourceBytes) {
            return "source ${inlineSource.size} exceeds maxSourceBytes ${limits.maxSourceBytes}"
        }
        if (inlineSource.size > JsProtocol.PARCEL_INLINE_MAX_BYTES) {
            return "inline source above parcel cap; use a source PFD"
        }
        return null
    }

    /** PFD/inline consistency and size caps for the input payload. */
    @Suppress("ReturnCount") // one return per distinct rejection reason
    private fun validateInput(
        request: JsExecutionRequest,
        envelope: JsExecutionWire.ExecuteEnvelope,
    ): String? {
        val limits = request.limits
        val inlineInput = request.inputJsonUtf8
        if (envelope.inputPfd != null) {
            if (inlineInput.isNotEmpty()) return "input must not be inline when an input PFD is provided"
            if (envelope.inputTotalBytes < 0) return "negative input length"
            if (envelope.inputTotalBytes > limits.maxInputBytes) {
                return "input ${envelope.inputTotalBytes} exceeds maxInputBytes ${limits.maxInputBytes}"
            }
            return null
        }
        if (envelope.inputTotalBytes != inlineInput.size.toLong()) {
            return "input length mismatch (declared ${envelope.inputTotalBytes}, inline ${inlineInput.size})"
        }
        if (inlineInput.size > limits.maxInputBytes) {
            return "input ${inlineInput.size} exceeds maxInputBytes ${limits.maxInputBytes}"
        }
        if (inlineInput.size > JsProtocol.PARCEL_INLINE_MAX_BYTES) {
            return "inline input above parcel cap; use an input PFD"
        }
        return null
    }

    /**
     * HXA-052 payload contract checks on the MATERIALIZED bytes (the client
     * enforces the same rules pre-bind; this is the defense-in-depth re-check for
     * direct binder users that bypass the client): the source must decode as
     * UTF-8, and a non-empty input must be exactly one valid JSON document — the
     * wrapper's `JSON.parse` only ever sees host-validated input.
     */
    @Suppress("ReturnCount") // one return per distinct payload violation
    fun validatePayload(
        sourceBytes: ByteArray,
        inputBytes: ByteArray,
    ): String? {
        if (JsJsonDocument.decodeUtf8Strict(sourceBytes) == null) return "source is not valid UTF-8"
        if (inputBytes.isNotEmpty() && !JsJsonDocument.isValidJson(inputBytes)) {
            return "input is not a valid JSON document"
        }
        return null
    }
}
