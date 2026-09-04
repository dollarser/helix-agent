package com.helix.app.provider

import com.helix.app.internal.LineStore
import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.CapabilityProbe
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderCapabilities.Companion.toJsonString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Persisted connection-test outcome per provider id (HXA-028: the UI must
 * distinguish 未测试 / 已通过 / 未通过 and must never show an untested provider
 * as "已可用"). Survives process restarts; written only by
 * [ProviderService.runConnectionTest].
 *
 * Line format:
 * `providerId|state|atMillis|phase|code|retryable|capabilitiesJson|[modelIdsJson]`
 * (7 or 8 fields) where `state` is PASSED|FAILED, `phase`/`code` are `0`/`-`
 * when absent and `capabilitiesJson` is the canonical [ProviderCapabilities]
 * snapshot for PASSED rows (PROBED source). The OPTIONAL 8th field (HXA-059) is
 * the JSON array of the backend model list on PASSED rows; it is omitted when
 * there is no list (no list / the backend does not expose one), so 7-field
 * lines written before HXA-059 stay valid and read back as `modelIds = null`.
 * Provider ids are `RandomIdGenerator` output (alphanumeric + `-`/`_`), and
 * `split("|", limit = 8)` keeps field 8 intact even when a model id contains
 * a `|` (the limit stops the split, the remainder rides in field 8).
 */
class ProviderTestStatusStore(
    store: LineStore,
) {
    private val backing = store

    fun statusFor(providerId: String): ConnectionTestStatus {
        val fields = backing.lines(KEY).firstOrNull { it.startsWith("$providerId|") }?.split("|", limit = 8)
        return parse(fields, fallback = ConnectionTestStatus.Untested)
    }

    /**
     * Parses one stored line. Any parse failure of the CORE fields (corruption,
     * unknown enum, bad capabilities JSON) degrades to the CONSERVATIVE
     * fallback — a corrupt row must never be read as "passed" (fail-closed,
     * doc 02 section 13). The OPTIONAL 8th field (the HXA-059 model list) is
     * parsed INDEPENDENTLY: a bad list degrades to `modelIds = null` while the
     * PASSED status itself is kept (the list is display data; losing it must
     * not pretend the provider is untested). The caught exceptions are
     * intentionally discarded: the row is already isolated and the fallback /
     * null outcome is fixed, so the exception objects carry nothing the caller
     * can use.
     */
    @Suppress("SwallowedException")
    private fun parse(
        fields: List<String>?,
        fallback: ConnectionTestStatus,
    ): ConnectionTestStatus {
        if (fields == null || fields.size < 7) return fallback
        return try {
            when (fields[1]) {
                "PASSED" -> {
                    ConnectionTestStatus.Passed(
                        atMillis = fields[2].toLong(),
                        capabilities = ProviderCapabilities.parse(fields[6]),
                        // 7-field (pre-HXA-059) rows read back as "no list".
                        modelIds = parseModelIds(fields.getOrNull(7)),
                    )
                }

                "FAILED" -> {
                    ConnectionTestStatus.Failed(
                        atMillis = fields[2].toLong(),
                        phase = fields[3].toInt(),
                        code = ModelErrorCode.valueOf(fields[4]),
                        retryable = fields[5].toBoolean(),
                    )
                }

                else -> {
                    fallback
                }
            }
        } catch (e: IllegalArgumentException) {
            fallback
        } catch (e: NumberFormatException) {
            fallback
        }
    }

    /**
     * The HXA-059 model list from field 8: a JSON array of strings, re-run
     * through the probe's normalization (drop blanks / de-dup / bound) so a
     * hand-corrupted or hostile file can never grow the UI list without bound.
     * Any deviation from the exact JSON-string-array shape → `null` (fail
     * closed: the list is dropped, the PASSED status is kept).
     */
    private fun parseModelIds(raw: String?): List<String>? =
        raw?.let { strictStringArray(it) }?.let(CapabilityProbe::normalizeModelIds)

    /**
     * Strict parse of field 8 into a `List<String>`: the value must be a JSON
     * array whose entries are ALL string primitives. Any deviation (non-array
     * element, a non-primitive or non-string entry, malformed JSON) → `null`
     * (fail closed: the whole list is dropped).
     */
    @Suppress("SwallowedException")
    private fun strictStringArray(raw: String): List<String>? {
        // malformed JSON or a non-array element both disqualify the whole list
        val array: JsonArray =
            try {
                Json.parseToJsonElement(raw) as? JsonArray
            } catch (e: IllegalArgumentException) {
                null
            } ?: return null
        val ids = ArrayList<String>()
        var ok = true
        for (entry in array) {
            if (!ok) break
            val id = stringEntry(entry)
            if (id == null) {
                ok = false // a non-string entry disqualifies the WHOLE list
            } else {
                ids += id
            }
        }
        return if (ok) ids else null
    }

    /** One array entry as a string; `null` when it is not a string primitive. */
    @Suppress("SwallowedException")
    private fun stringEntry(entry: JsonElement): String? =
        try {
            val primitive = entry.jsonPrimitive
            if (primitive.isString) primitive.content else null
        } catch (e: IllegalStateException) {
            null
        }

    /** The HXA-059 model list as the strict JSON array stored in field 8. */
    private fun modelIdsJson(ids: List<String>): String =
        buildJsonArray { ids.forEach { id -> add(JsonPrimitive(id)) } }.toString()

    /**
     * Records a PASSED run (with the PROBED capabilities snapshot and the
     * HXA-059 backend model list). An empty/absent list writes the 7-field
     * line (backward compatible with pre-HXA-059 readers).
     */
    fun recordPassed(
        providerId: String,
        atMillis: Long,
        capabilities: ProviderCapabilities,
        modelIds: List<String>? = null,
    ) {
        val modelsField = modelIds?.takeIf { it.isNotEmpty() }?.let(::modelIdsJson)
        val line =
            if (modelsField == null) {
                "$providerId|PASSED|$atMillis|0|-|false|${toJsonString(capabilities)}"
            } else {
                "$providerId|PASSED|$atMillis|0|-|false|${toJsonString(capabilities)}|$modelsField"
            }
        replace(providerId, line)
    }

    /** Records a FAILED run (phase 1..4 + the safe error code class). */
    fun recordFailed(
        providerId: String,
        atMillis: Long,
        phase: Int,
        code: ModelErrorCode,
        retryable: Boolean,
    ) {
        replace(providerId, "$providerId|FAILED|$atMillis|$phase|${code.name}|$retryable|-")
    }

    /** Drops the recorded status (provider deleted). */
    fun clear(providerId: String) {
        backing.setLines(KEY, backing.lines(KEY).filterNot { it.startsWith("$providerId|") })
    }

    private fun replace(
        providerId: String,
        line: String,
    ) {
        val current = backing.lines(KEY).filterNot { it.startsWith("$providerId|") }
        backing.setLines(KEY, current + line)
    }

    private companion object {
        const val KEY = "provider_test_status"
    }
}
