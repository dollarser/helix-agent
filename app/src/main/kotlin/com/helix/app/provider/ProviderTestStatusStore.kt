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
 * Canonical line format:
 * `providerId|state|atMillis|phase|code|retryable|capabilitiesJson|modelIdsJson`.
 * Exactly eight fields are required. `modelIdsJson` is a JSON string array or literal `null`;
 * FAILED rows use `-` for capabilities and `null` for model ids. Provider ids are bounded
 * internal ids, and `split("|", limit = 8)` keeps the final JSON field intact.
 */
class ProviderTestStatusStore(
    store: LineStore,
) {
    private val backing = store
    val modelMetadata = ProviderModelMetadataStore(store)
    val selectedModels = ProviderSelectedModels(store)
    val modelEvidence = ProviderModelEvidenceStore(store)
    val accounts = ManagedAccountStore(store)

    fun statusFor(providerId: String): ConnectionTestStatus {
        val fields = backing.lines(KEY).firstOrNull { it.startsWith("$providerId|") }?.split("|", limit = 8)
        return parse(fields, fallback = ConnectionTestStatus.Untested)
    }

    /**
     * Parses one canonical stored line. Core-field corruption degrades to the conservative
     * fallback, so corrupt data is never read as passed. Model-list corruption drops only the
     * display list while preserving an otherwise valid PASSED result.
     */
    @Suppress("SwallowedException")
    private fun parse(
        fields: List<String>?,
        fallback: ConnectionTestStatus,
    ): ConnectionTestStatus {
        if (fields == null || fields.size != FIELD_COUNT) return fallback
        return try {
            when (fields[1]) {
                "PASSED" -> {
                    ConnectionTestStatus.Passed(
                        atMillis = fields[2].toLong(),
                        capabilities = ProviderCapabilities.parse(fields[6]),
                        modelIds = parseModelIds(fields[7]),
                    )
                }

                "FAILED" -> {
                    ConnectionTestStatus.Failed(
                        atMillis = fields[2].toLong(),
                        phase = fields[3].toInt(),
                        code = ModelErrorCode.valueOf(fields[4]),
                        retryable = fields[5].toBooleanStrict(),
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
     * Field 8 is JSON `null` or a string array. Arrays are normalized and bounded again on read;
     * malformed lists are dropped without changing the core connection-test result.
     */
    private fun parseModelIds(raw: String): List<String>? =
        if (raw == NO_MODEL_LIST) null else strictStringArray(raw)?.let(CapabilityProbe::normalizeModelIds)

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

    /** The model list as the strict JSON array stored in field 8. */
    private fun modelIdsJson(ids: List<String>): String =
        buildJsonArray { ids.forEach { id -> add(JsonPrimitive(id)) } }.toString()

    /** Records a PASSED run with probed capabilities and the canonical field-8 value. */
    fun recordPassed(
        providerId: String,
        atMillis: Long,
        capabilities: ProviderCapabilities,
        modelIds: List<String>? = null,
    ) {
        val modelsField = modelIds?.takeIf { it.isNotEmpty() }?.let(::modelIdsJson) ?: NO_MODEL_LIST
        replace(providerId, "$providerId|PASSED|$atMillis|0|-|false|${toJsonString(capabilities)}|$modelsField")
    }

    /** Records a FAILED run (phase 1..4 + the safe error code class). */
    fun recordFailed(
        providerId: String,
        atMillis: Long,
        phase: Int,
        code: ModelErrorCode,
        retryable: Boolean,
    ) {
        replace(providerId, "$providerId|FAILED|$atMillis|$phase|${code.name}|$retryable|-|$NO_MODEL_LIST")
    }

    /** Drops the recorded status (provider deleted). */
    fun clear(providerId: String) {
        modelEvidence.clear(providerId)
        synchronized(WRITE_LOCK) {
            backing.setLines(KEY, backing.lines(KEY).filterNot { it.startsWith("$providerId|") })
        }
    }

    private fun replace(
        providerId: String,
        line: String,
    ) {
        synchronized(WRITE_LOCK) {
            val current = backing.lines(KEY).filterNot { it.startsWith("$providerId|") }
            backing.setLines(KEY, current + line)
        }
    }

    private companion object {
        val WRITE_LOCK = Any()
        const val KEY = "provider_test_status"
        const val FIELD_COUNT = 8
        const val NO_MODEL_LIST = "null"
    }
}
