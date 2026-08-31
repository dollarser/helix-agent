package com.helix.app.provider

import com.helix.app.internal.LineStore
import com.helix.core.model.ModelErrorCode
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderCapabilities.Companion.toJsonString

/**
 * Persisted connection-test outcome per provider id (HXA-028: the UI must
 * distinguish 未测试 / 已通过 / 未通过 and must never show an untested provider
 * as "已可用"). Survives process restarts; written only by
 * [ProviderService.runConnectionTest].
 *
 * Line format (dependency-free, no JSON):
 * `providerId|state|atMillis|phase|code|retryable|capabilitiesJson` (7 fields)
 * where `state` is PASSED|FAILED, `phase`/`code` are `0`/`-` when absent and
 * `capabilitiesJson` is the canonical [ProviderCapabilities] snapshot for
 * PASSED rows (PROBED source). Provider ids are `RandomIdGenerator` output
 * (alphanumeric + `-`/`_`) and the JSON contains no `|`, so the split is
 * unambiguous.
 */
class ProviderTestStatusStore(
    store: LineStore,
) {
    private val backing = store

    fun statusFor(providerId: String): ConnectionTestStatus {
        val fields = backing.lines(KEY).firstOrNull { it.startsWith("$providerId|") }?.split("|", limit = 7)
        return parse(fields, fallback = ConnectionTestStatus.Untested)
    }

    /**
     * Parses one stored line. Any parse failure (corruption, unknown enum,
     * bad JSON) degrades to the CONSERVATIVE fallback — a corrupt row must
     * never be read as "passed" (fail-closed, doc 02 section 13). The caught
     * exceptions are intentionally discarded: the row is already isolated and
     * the reason string is fixed by this fallback, so the exception objects
     * carry nothing the caller can use.
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
                    ConnectionTestStatus.Passed(fields[2].toLong(), ProviderCapabilities.parse(fields[6]))
                }

                "FAILED" -> {
                    ConnectionTestStatus.Failed(
                        atMillis = fields[2].toLong(),
                        phase = fields[3].toInt(),
                        codeLabel = ConnectionTestMapping.codeLabel(ModelErrorCode.valueOf(fields[4])),
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

    /** Records a PASSED run (with the PROBED capabilities snapshot). */
    fun recordPassed(
        providerId: String,
        atMillis: Long,
        capabilities: ProviderCapabilities,
    ) {
        replace(
            providerId,
            "$providerId|PASSED|$atMillis|0|-|false|${toJsonString(capabilities)}",
        )
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
