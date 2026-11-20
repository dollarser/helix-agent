package com.helix.provider.api.wire

import com.helix.provider.api.ServerErrorSummary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** UI-only diagnostic fields. Do not log this object or interpret detail as instructions. */
public class HttpFailureDetails(
    public val status: Int,
    public val detail: String? = null,
    public val requestId: String? = null,
    public val retryAfterMillis: Long? = null,
    public val errorCode: String? = null,
    public val retryable: Boolean? = null,
)

/** Bounded read; a failed diagnostic body never replaces the original HTTP failure. Caller closes body. */
public suspend fun readHttpFailure(response: WireResponse): HttpFailureDetails {
    val prefix = ByteArrayOutputStream()
    try {
        response.body.forEachChunk {
            prefix.write(it, 0, minOf(it.size, 8192 - prefix.size()))
            prefix.size() < 8192
        }
    } catch (_: IOException) {
        // Headers remain useful even when the error body is unavailable.
    }
    return httpFailureDetails(response.status, response.headers, prefix.toString(Charsets.UTF_8.name()))
}

public fun httpFailureDetails(
    status: Int,
    headers: Map<String, List<String>>,
    body: String? = null,
    nowMillis: Long = System.currentTimeMillis(),
): HttpFailureDetails {
    val parsed =
        try {
            body?.takeIf { it.length <= 8192 }?.let { Json.parseToJsonElement(it) as? JsonObject }
        } catch (_: kotlinx.serialization.SerializationException) {
            null
        }
    val error = parsed?.get("error") as? JsonObject
    val detail = (parsed?.get("detail") ?: error?.get("message") ?: parsed?.get("message")) as? JsonPrimitive
    val requestId = header(headers, "x-request-id")?.takeIf { it.matches(Regex("[A-Za-z0-9._:-]{1,128}")) }
    val retry =
        header(headers, "retry-after")?.let { raw ->
            raw
                .toLongOrNull()
                ?.takeIf { it >= 0 }
                ?.coerceAtMost(Long.MAX_VALUE / 1000)
                ?.times(1000)
                ?: try {
                    val date = ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
                    val deadline = date.toInstant().toEpochMilli()
                    (deadline - nowMillis).coerceAtLeast(0)
                } catch (_: java.time.format.DateTimeParseException) {
                    null
                }
        }
    return HttpFailureDetails(
        status,
        detail?.takeIf { it.isString }?.content?.let { ServerErrorSummary.redact(it, emptyList()) },
        requestId,
        retry,
        ((parsed?.get("error_code") ?: error?.get("code")) as? JsonPrimitive)
            ?.content
            ?.takeIf { it.matches(Regex("[A-Za-z0-9_]{1,96}")) },
        ((parsed?.get("retryable") ?: error?.get("retryable")) as? JsonPrimitive)?.content?.toBooleanStrictOrNull(),
    )
}

private fun header(
    headers: Map<String, List<String>>,
    name: String,
): String? =
    headers.entries
        .firstOrNull { it.key.equals(name, true) }
        ?.value
        ?.firstOrNull()
