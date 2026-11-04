package com.helix.core.model

/** Failure metadata plus bounded sanitized server fields; never a raw URL, header, prompt or response body. */
data class ModelFailureDiagnostic(
    val stage: Stage,
    val reason: Reason,
    val httpStatus: Int? = null,
    val responseType: ResponseType? = null,
    val receivedBytes: Long = 0,
    val receivedChunks: Long = 0,
    val elapsedMillis: Long = 0,
    val requestIdHash: String? = null,
    val adapterRetryable: Boolean? = null,
    val serverError: ServerError? = null,
    val errorBodyState: ErrorBodyState? = null,
    val retryAfterMillis: Long? = null,
    val serverMessage: String? = null,
    val serverCode: String? = null,
    val errorParameter: String? = null,
) {
    enum class Stage { CONFIGURATION, CONNECT, HTTP, STREAM }

    enum class ResponseType { EVENT_STREAM, JSON, HTML, OTHER, MISSING }

    enum class ErrorBodyState { EMPTY, JSON, NON_JSON, TRUNCATED, READ_FAILED }

    enum class ServerError {
        CONTEXT_LENGTH_EXCEEDED,
        INVALID_REQUEST_ERROR,
        MODEL_NOT_FOUND,
        RATE_LIMIT_EXCEEDED,
        INSUFFICIENT_QUOTA,
        AUTHENTICATION_ERROR,
        PERMISSION_ERROR,
        OVERLOADED_ERROR,
        INVALID_API_KEY,
        UNKNOWN,
    }

    enum class Reason {
        HTTP_STATUS,
        SIGN_IN_REQUIRED,
        ACCOUNT_CHANGED,
        ACCOUNT_STATE_UNAVAILABLE,
        INVALID_HTTP_PROTOCOL,
        INVALID_CONFIGURATION,
        DNS,
        TLS,
        CONNECTION,
        TIMEOUT,
        IO,
        MISSING_TERMINAL,
        INVALID_SSE,
        INVALID_JSON,
        INVALID_TOOL_CALL,
        INVALID_FINISH_REASON,
        AFTER_TERMINAL,
        VENDOR_ERROR,
        INVALID_SCHEMA,
    }

    companion object {
        const val MAX_RETRY_AFTER_MILLIS = 300_000L
    }

    init {
        require(serverMessage == null || serverMessage.toByteArray().size <= 768)
        require(serverCode == null || serverCode.length <= 96)
        require(errorParameter == null || errorParameter.length <= 128)
        require(retryAfterMillis == null || retryAfterMillis in 0..MAX_RETRY_AFTER_MILLIS)
        require(httpStatus == null || httpStatus in 100..599)
        require(receivedBytes >= 0 && receivedChunks >= 0 && elapsedMillis >= 0)
        require(requestIdHash == null || Regex("[a-f0-9]{64}").matches(requestIdHash))
    }
}
