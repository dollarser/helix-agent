package com.helix.provider.api

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelFailureDiagnostic
import com.helix.core.model.ModelFailureDiagnostic.ErrorBodyState
import com.helix.core.model.ModelFailureDiagnostic.Reason
import com.helix.core.model.ModelFailureDiagnostic.ResponseType
import com.helix.core.model.ModelFailureDiagnostic.ServerError
import com.helix.core.model.ModelFailureDiagnostic.Stage
import com.helix.provider.api.wire.WireRequestStateException
import com.helix.provider.api.wire.WireResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.SSLException

/** Per-exchange counters and selected sanitized error fields; never the raw request or response body. */
internal class WireFailureEvidence {
    private val started = System.nanoTime()
    var response: WireResponse? = null
    var bytes = 0L
    var chunks = 0L

    private var serverError: ServerError? = null
    private var bodyState: ErrorBodyState? = null
    private var serverMessage: String? = null
    private var serverCode: String? = null
    private var errorParameter: String? = null
    private var secrets: List<String> = emptyList()

    fun requestHeaders(headers: Map<String, String>) {
        val sensitive = Regex("(?i)(authorization|key|token|cookie)")
        secrets =
            headers
                .filterKeys { sensitive.containsMatchIn(it) }
                .values
                .flatMap { listOf(it, it.removePrefix("Bearer ").removePrefix("bearer ")) }
    }

    fun transport(
        error: IOException,
        stage: Stage,
    ): ModelEvent.Error {
        val retryable =
            error !is WireRequestStateException &&
                error !is SSLException && error !is java.net.ProtocolException &&
                error !is java.net.MalformedURLException && error !is java.net.UnknownServiceException
        val reason = reason(error)
        val code =
            when {
                error is WireRequestStateException -> ModelErrorCode.AUTH
                reason in setOf(Reason.INVALID_HTTP_PROTOCOL, Reason.INVALID_CONFIGURATION) -> ModelErrorCode.PROTOCOL
                error is SocketTimeoutException -> ModelErrorCode.TIMEOUT
                else -> ModelErrorCode.TRANSPORT
            }
        return ModelEvent.Error(code, retryable, failure(stage, reason, retryable))
    }

    fun annotate(
        event: ModelEvent.Error,
        rule: String?,
    ): ModelEvent.Error {
        val reason =
            if (event.code == ModelErrorCode.PROTOCOL) {
                protocolReason(rule)
            } else {
                Reason.VENDOR_ERROR
            }
        val diagnostic = failure(Stage.STREAM, reason, event.retryable)
        val invalidType =
            reason == Reason.MISSING_TERMINAL &&
                diagnostic.responseType in setOf(ResponseType.HTML, ResponseType.JSON)
        return event.copy(
            retryable = event.retryable && !invalidType,
            diagnostic =
                if (invalidType) {
                    diagnostic.copy(reason = Reason.INVALID_SCHEMA, adapterRetryable = false)
                } else {
                    diagnostic
                },
        )
    }

    suspend fun httpError(): ModelEvent.Error {
        val (code, retryable) =
            com.helix.provider.api.wire
                .mapHttpStatus(requireNotNull(response).status)
        val diagnostic = httpFailure(retryable)
        val canRetry =
            retryable && diagnostic.serverError !in
                setOf(
                    ServerError.INSUFFICIENT_QUOTA,
                    ServerError.INVALID_API_KEY,
                    ServerError.AUTHENTICATION_ERROR,
                    ServerError.PERMISSION_ERROR,
                )
        return ModelEvent.Error(code, canRetry, diagnostic.copy(adapterRetryable = canRetry))
    }

    suspend fun httpFailure(retryable: Boolean): ModelFailureDiagnostic {
        val prefix = ByteArrayOutputStream()
        var truncated = false
        try {
            requireNotNull(response).body.forEachChunk { chunk ->
                bytes += chunk.size
                chunks++
                val count = minOf(chunk.size, ERROR_PREFIX_BYTES - prefix.size())
                prefix.write(chunk, 0, count)
                truncated = prefix.size() == ERROR_PREFIX_BYTES
                !truncated
            }
            bodyState =
                when {
                    truncated -> ErrorBodyState.TRUNCATED
                    prefix.size() == 0 -> ErrorBodyState.EMPTY
                    else -> classifyBody(prefix.toString(Charsets.UTF_8.name()))
                }
        } catch (_: IOException) {
            // Preserve the original HTTP failure; incomplete diagnostic reads are explicit.
            bodyState = ErrorBodyState.READ_FAILED
        }
        return failure(Stage.HTTP, Reason.HTTP_STATUS, retryable)
    }

    private fun classifyBody(text: String): ErrorBodyState {
        val parsed =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: kotlinx.serialization.SerializationException) {
                null
            } ?: return ErrorBodyState.NON_JSON
        val error = parsed["error"] as? JsonObject ?: parsed
        val message = (error["message"] ?: error["detail"] ?: parsed["detail"]) as? JsonPrimitive
        serverMessage = message?.takeIf { it.isString }?.content?.let { ServerErrorSummary.redact(it, secrets) }
        serverCode =
            ((error["code"] ?: error["type"]) as? JsonPrimitive)
                ?.content
                ?.takeIf { it.matches(Regex("[A-Za-z][A-Za-z_.-]{0,95}")) }
                ?.let { ServerErrorSummary.redact(it, secrets).take(96) }
        errorParameter =
            (error["param"] as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { it.matches(Regex("[A-Za-z0-9_.\\[\\]-]{1,128}")) }
                ?.let { ServerErrorSummary.redact(it, secrets).take(128) }
        val labels = listOf("code", "type").mapNotNull { (error[it] as? JsonPrimitive)?.content }
        serverError = labels.firstNotNullOfOrNull { label ->
            ServerError.entries.firstOrNull { it != ServerError.UNKNOWN && it.name.equals(label, true) }
        } ?: ServerError.UNKNOWN
        return ErrorBodyState.JSON
    }

    fun failure(
        stage: Stage,
        reason: Reason,
        retryable: Boolean,
    ): ModelFailureDiagnostic {
        val headers = response?.headers.orEmpty()
        val type =
            headers.entries
                .firstOrNull { it.key.equals("content-type", true) }
                ?.value
                ?.firstOrNull()
        val requestId =
            headers.entries
                .firstOrNull {
                    it.key.lowercase() in setOf("x-request-id", "request-id", "openai-request-id", "x-amzn-requestid")
                }?.value
                ?.firstOrNull()
        return ModelFailureDiagnostic(
            stage,
            reason,
            response?.status,
            when (type?.substringBefore(';')?.trim()?.lowercase()) {
                null -> ResponseType.MISSING
                "text/event-stream" -> ResponseType.EVENT_STREAM
                "application/json" -> ResponseType.JSON
                "text/html" -> ResponseType.HTML
                else -> ResponseType.OTHER
            },
            bytes,
            chunks,
            ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0),
            requestId?.takeIf { it.length <= 1024 }?.let {
                val digest = MessageDigest.getInstance("SHA-256").digest(it.toByteArray())
                digest.joinToString("") { b -> "%02x".format(b) }
            },
            retryable,
            serverError,
            bodyState,
            retryAfter(headers),
            serverMessage,
            serverCode,
            errorParameter,
        )
    }

    private fun retryAfter(headers: Map<String, List<String>>): Long? =
        headers.entries.firstOrNull { it.key.equals("retry-after", true) }?.value?.firstOrNull()?.let { value ->
            val millis =
                value.toLongOrNull()?.takeIf { it >= 0 }?.let { it.coerceAtMost(300) * 1000 }
                    ?: try {
                        val deadline =
                            java.time.ZonedDateTime.parse(
                                value,
                                java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME,
                            )
                        (deadline.toInstant().toEpochMilli() - System.currentTimeMillis()).coerceAtLeast(0)
                    } catch (_: java.time.format.DateTimeParseException) {
                        null
                    }
            millis?.coerceAtMost(ModelFailureDiagnostic.MAX_RETRY_AFTER_MILLIS)
        }

    companion object {
        private const val ERROR_PREFIX_BYTES = 8192

        fun reason(error: IOException): Reason =
            when (error) {
                is WireRequestStateException -> {
                    when (error.reason) {
                        WireRequestStateException.Reason.SIGN_IN_REQUIRED -> {
                            Reason.SIGN_IN_REQUIRED
                        }

                        WireRequestStateException.Reason.ACCOUNT_CHANGED -> {
                            Reason.ACCOUNT_CHANGED
                        }

                        WireRequestStateException.Reason.ACCOUNT_UNAVAILABLE -> {
                            Reason.ACCOUNT_STATE_UNAVAILABLE
                        }
                    }
                }

                is java.net.ProtocolException -> {
                    Reason.INVALID_HTTP_PROTOCOL
                }

                is java.net.MalformedURLException, is java.net.UnknownServiceException -> {
                    Reason.INVALID_CONFIGURATION
                }

                is SocketTimeoutException -> {
                    Reason.TIMEOUT
                }

                is UnknownHostException -> {
                    Reason.DNS
                }

                is SSLException -> {
                    Reason.TLS
                }

                is ConnectException -> {
                    Reason.CONNECTION
                }

                else -> {
                    Reason.IO
                }
            }

        /** Never export the decoder's potentially vendor-derived detail itself. */
        fun protocolReason(rule: String?): Reason =
            when {
                rule == "missing terminal" -> Reason.MISSING_TERMINAL
                rule?.startsWith("sse:") == true -> Reason.INVALID_SSE
                rule?.startsWith("json:") == true -> Reason.INVALID_JSON
                rule?.contains("after terminal") == true -> Reason.AFTER_TERMINAL
                rule?.startsWith("event after") == true -> Reason.AFTER_TERMINAL
                rule?.contains("finish reason") == true -> Reason.INVALID_FINISH_REASON
                rule?.contains("tool") == true -> Reason.INVALID_TOOL_CALL
                rule?.contains("arguments") == true -> Reason.INVALID_TOOL_CALL
                rule?.contains("function call") == true -> Reason.INVALID_TOOL_CALL
                rule?.contains("error object") == true -> Reason.VENDOR_ERROR
                else -> Reason.INVALID_SCHEMA
            }
    }
}
