package com.helix.provider.api

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.SecretAlias
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import com.helix.provider.api.wire.mapHttpStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Shared transport skeleton of the protocol providers (HXA-025): encode → open →
 * non-2xx mapping → decode loop → finish, plus the model-list helper. The
 * protocol-specific pieces (request encoding, stream decoding, auth headers,
 * resource paths) are injected by the concrete providers in the adapter
 * modules — the protocol island discipline (no cross-protocol fallback) is
 * preserved: this base never guesses a different protocol after a failure.
 *
 * [stream] maps every transport outcome onto the stream contract (HXA-021):
 * - non-2xx HTTP → one terminal [ModelEvent.Error] from [mapHttpStatus];
 * - connection-level [java.io.IOException] → [ModelErrorCode.TRANSPORT]
 *   (retryable: DNS/TLS/peer reset are connection-level per the doc 02 mapping);
 *   [java.net.SocketTimeoutException] → [ModelErrorCode.TIMEOUT] (retryable);
 * - 2xx → the decoder's event sequence, which ends with a terminal (the
 *   decoders guarantee exactly one).
 *
 * The response body is always closed in a `finally`, including on
 * cancellation of the collecting coroutine.
 */
public abstract class WireModelProvider(
    override val descriptor: ProviderDescriptor,
    protected val credentials: CredentialLookup,
    protected val wire: WireClient,
    protected val encoder: RequestEncoder,
    protected val newDecoder: () -> StreamDecoder,
    protected val secretAlias: SecretAlias,
    protected val extraHeaders: Map<String, String>,
) : ModelProvider {
    /**
     * The streaming resource path (e.g. `/chat/completions`), appended to the
     * endpoint's path. The endpoint path is the API root (e.g. `/v1`); the
     * catalog templates (HXA-026) prefill it accordingly.
     */
    protected abstract fun streamPath(): String

    /** The model-list resource path; `null` when the service has no list endpoint. */
    protected open fun modelsPath(): String? = null

    /**
     * The protocol's authentication headers, resolved at call time from
     * [secretAlias] via [credentials] (a lookup failure throws and the request
     * is never sent). Must not return transport names (`host`, `content-length`)
     * — the allowlist-validated [extraHeaders] already covers the user layer.
     */
    protected abstract fun authHeaders(): Map<String, String>

    /**
     * The connection-level exceptions caught below are INTENTIONALLY converted
     * into the terminal [ModelEvent.Error] event (the HXA-021 stream contract
     * is total: a stream ends in a terminal, it never throws transport
     * failures at the collector). [ModelEvent.Error] carries no detail field —
     * the cause class is already encoded in the error code — so the exception
     * object has no further use; suppressing SwallowedException documents that
     * conversion rather than hiding a bug.
     */
    @Suppress("SwallowedException") // connection exception converted to the terminal Error event
    override fun stream(request: ModelRequest): Flow<ModelEvent> =
        flow {
            val url = wireUrl(streamPath())
            val bodyBytes = encoder.encode(request).toByteArray(Charsets.UTF_8)
            // Credential/header resolution is OUTSIDE the transport try: a
            // missing credential or a header collision is a configuration
            // error that propagates as-is (never a transport error event).
            val headers = headersFor()
            val response =
                try {
                    wire.open(
                        WireRequest(
                            "POST",
                            url,
                            headers,
                            bodyBytes,
                        ),
                    )
                } catch (_: SocketTimeoutException) {
                    emit(ModelEvent.Error(ModelErrorCode.TIMEOUT, retryable = true))
                    return@flow
                } catch (_: IOException) {
                    // DNS/TLS/peer reset: the connection-level failure class.
                    emit(ModelEvent.Error(ModelErrorCode.TRANSPORT, retryable = true))
                    return@flow
                }
            try {
                if (response.status !in 200..299) {
                    val (code, retryable) = mapHttpStatus(response.status)
                    emit(ModelEvent.Error(code, retryable))
                    return@flow
                }
                val decoder = newDecoder()
                response.body.forEachChunk { chunk ->
                    val events = decoder.feed(chunk)
                    for (event in events) {
                        emit(event)
                    }
                    true
                }
                val tail = decoder.finish()
                for (event in tail) {
                    emit(event)
                }
            } finally {
                response.body.close()
            }
        }

    override suspend fun listModels(): ModelCatalogResult {
        val path = modelsPath() ?: return ModelCatalogResult.Unsupported
        var response: WireResponse? = null
        var ioFailureResult: ModelCatalogResult? = null
        try {
            response = wire.open(WireRequest("GET", wireUrl(path), headersFor(), null))
        } catch (e: SocketTimeoutException) {
            ioFailureResult = ioFailure(ModelErrorCode.TIMEOUT, e)
        } catch (e: IOException) {
            ioFailureResult = ioFailure(ModelErrorCode.TRANSPORT, e)
        }
        val open = response
        return when {
            open == null -> {
                ioFailureResult
                    ?: error("unreachable: open either succeeded or recorded a failure")
            }

            else -> {
                val body = open.body
                try {
                    if (open.status in 200..299) {
                        parseModelIds(body.bytes().decodeToString())
                    } else {
                        val (code, retryable) = mapHttpStatus(open.status)
                        ModelCatalogResult.Failed(
                            code,
                            boundedDetail("HTTP ${open.status} on /$path"),
                            retryable,
                        )
                    }
                } finally {
                    body.close()
                }
            }
        }
    }

    override suspend fun validateConfiguration(): ProviderCheckResult {
        if (modelsPath() != null) {
            return when (val result = listModels()) {
                is ModelCatalogResult.Listed, is ModelCatalogResult.Unsupported -> {
                    ProviderCheckResult.Ok
                }

                is ModelCatalogResult.Failed -> {
                    ProviderCheckResult.Failed(result.code, result.detail, result.retryable)
                }
            }
        }
        // Services without a model list (the official Anthropic API) are
        // checked with a minimal stream instead: transport + auth + the stream
        // contract in one bounded call.
        return validateByStream()
    }

    private suspend fun validateByStream(): ProviderCheckResult {
        var ioFailureResult: ProviderCheckResult? = null
        val events: List<ModelEvent> =
            try {
                stream(
                    ModelRequest(
                        model = descriptor.model,
                        messages = listOf(ModelMessage(ModelRole.USER, "ping")),
                        maxOutputTokens = VALIDATION_MAX_OUTPUT_TOKENS,
                    ),
                ).toList()
            } catch (e: SocketTimeoutException) {
                ioFailureResult = ioFailureCheck(ModelErrorCode.TIMEOUT, e)
                emptyList()
            } catch (e: IOException) {
                ioFailureResult = ioFailureCheck(ModelErrorCode.TRANSPORT, e)
                emptyList()
            }
        val failed = ioFailureResult
        if (failed != null) return failed
        val error = events.filterIsInstance<ModelEvent.Error>().firstOrNull()
        return when {
            error != null -> {
                ProviderCheckResult.Failed(
                    error.code,
                    boundedDetail("validation stream failed: ${error.code}"),
                    error.retryable,
                )
            }

            events.any { it is ModelEvent.Completed || it is ModelEvent.Refusal } -> {
                ProviderCheckResult.Ok
            }

            else -> {
                ProviderCheckResult.Failed(
                    ModelErrorCode.PROTOCOL,
                    boundedDetail("validation stream had no terminal"),
                    retryable = true,
                )
            }
        }
    }

    /**
     * Strict parse of the OpenAI-compatible `{"data":[{"id":...}]}` list body.
     * Each of the four vendor contract violations (not JSON / not an object /
     * no data array / entry without an id string) and the bound check is a
     * distinct PROTOCOL failure — all are folded into one result here.
     */
    protected open fun parseModelIds(body: String): ModelCatalogResult {
        val parsed = parseListObject(body)
        val collected = ArrayList<String>()
        var problem: String? = (parsed as? ModelListParse.Problem)?.detail
        if (parsed is ModelListParse.Ok) {
            for (entry in parsed.data) {
                val text = (entry as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.content }
                if (text == null) {
                    problem = "model entry has no id string"
                    break
                }
                collected += text
            }
        }
        return when {
            problem != null -> {
                ModelCatalogResult.Failed(
                    ModelErrorCode.PROTOCOL,
                    boundedDetail(problem),
                    retryable = false,
                )
            }

            else -> {
                try {
                    ModelCatalogResult.Listed(collected)
                } catch (e: IllegalArgumentException) {
                    ModelCatalogResult.Failed(
                        ModelErrorCode.PROTOCOL,
                        boundedDetail("model list violates bounds: ${e.message}"),
                        retryable = false,
                    )
                }
            }
        }
    }

    /**
     * Joins the endpoint's API root with the protocol resource path. The
     * default port for the scheme is omitted from the wire URL (canonical
     * form), and IPv6 literals are bracketed.
     */
    protected fun wireUrl(resource: String): String {
        val endpoint = descriptor.endpoint
        val hostPart =
            if (endpoint.host.contains(':')) {
                "[${endpoint.host}]"
            } else {
                endpoint.host
            }
        val authority =
            if (endpoint.port == defaultPortFor(endpoint.scheme)) {
                hostPart
            } else {
                "$hostPart:${endpoint.port}"
            }
        val joined =
            when {
                endpoint.path.isEmpty() -> "/$resource"
                endpoint.path.endsWith("/") -> "${endpoint.path}$resource"
                else -> "${endpoint.path}/$resource"
            }
        return "${endpoint.scheme}://$authority$joined"
    }

    /**
     * Merges user headers, the protocol auth headers and the content type; any
     * case-insensitive name collision is a configuration error (fail closed —
     * a silent override of an auth header would be a credential bug).
     */
    private fun headersFor(): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        val layers: List<Map<String, String>> = listOf(extraHeaders, authHeaders())
        layers.forEach { layer ->
            layer.forEach { (name, value) ->
                val clash = merged.any { it.key.equals(name, ignoreCase = true) }
                require(!clash) { "duplicate provider header: $name" }
                merged[name] = value
            }
        }
        require(!merged.any { it.key.equals("Content-Type", ignoreCase = true) }) {
            "the content type is fixed by the protocol (application/json)"
        }
        merged["Content-Type"] = "application/json"
        return merged
    }

    internal companion object {
        const val VALIDATION_MAX_OUTPUT_TOKENS = 8L

        private fun defaultPortFor(scheme: String): Int = if (scheme == "https") 443 else 80
    }
}

/** File-private IO-failure results (kept out of the class to bound its function count). */
private fun ioFailure(
    code: ModelErrorCode,
    cause: IOException,
): ModelCatalogResult =
    ModelCatalogResult.Failed(
        code,
        boundedDetail(cause::class.simpleName ?: "io failure"),
        retryable = true,
    )

private fun ioFailureCheck(
    code: ModelErrorCode,
    cause: IOException,
): ProviderCheckResult =
    ProviderCheckResult.Failed(
        code,
        boundedDetail(cause::class.simpleName ?: "io failure"),
        retryable = true,
    )

private sealed interface ModelListParse {
    data class Ok(
        val data: JsonArray,
    ) : ModelListParse

    data class Problem(
        val detail: String,
    ) : ModelListParse
}

/**
 * Each return is a distinct vendor contract violation (not JSON / not an
 * object / no data array), folded into the [ModelListParse.Problem] class.
 */
@Suppress("ReturnCount") // three fail-closed returns, one per vendor violation
private fun parseListObject(body: String): ModelListParse {
    val element =
        try {
            Json.parseToJsonElement(body)
        } catch (e: IllegalArgumentException) {
            return ModelListParse.Problem("models body is not JSON: ${e::class.simpleName}")
        }
    val obj =
        element as? JsonObject
            ?: return ModelListParse.Problem("models body is not a JSON object")
    val data =
        obj["data"] as? JsonArray
            ?: return ModelListParse.Problem("models body has no data array")
    return ModelListParse.Ok(data)
}

/**
 * Resolves the plaintext credential for [alias] at request time (fail closed:
 * a missing or blank credential never reaches the wire). Top-level (not a
 * [WireModelProvider] member) so concrete providers in the adapter modules can
 * call it with their protected credentials/alias without widening the class.
 */
public fun resolveCredential(
    credentials: CredentialLookup,
    alias: SecretAlias,
): String {
    val value = credentials.lookup(alias)
    require(value.isNotBlank()) { "credential for $alias is blank" }
    return value
}
