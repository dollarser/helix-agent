package com.helix.app.account

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.math.BigDecimal
import java.time.Instant

internal enum class AccountUsageUnit { TOKENS, MINUTES, CHARACTERS }

internal data class AccountQuota(
    val model: String,
    val period: String,
    val timezone: String,
    val unit: AccountUsageUnit,
    val limit: BigDecimal,
    val used: BigDecimal,
    val reserved: BigDecimal,
    val remaining: BigDecimal,
    val resetAt: Instant? = null,
    val scope: String? = null,
)

internal data class AccountUsageTotal(
    val service: String,
    val requests: Long,
)

internal data class AccountUsageRecord(
    val service: String,
    val status: Long?,
)

internal data class AccountUsageSummary(
    val quotas: List<AccountQuota>,
    val today: String,
    val totals: List<AccountUsageTotal>,
    val records: List<AccountUsageRecord>,
)

/** Explicit production contract allowlist. Never infer remaining from used or reserved. */
internal fun AccountUsageDocuments.summary(): AccountUsageSummary {
    val lunaQuota =
        AccountQuota(
            luna.text("model"),
            luna.text("month"),
            luna.text("timezone"),
            AccountUsageUnit.TOKENS,
            luna.amount("monthly_tokens"),
            luna.amount("used_tokens"),
            luna.amount("reserved_tokens"),
            luna.amount("remaining_tokens"),
            luna.resetInstant(),
            scope = (luna["scope"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
        )

    fun audioQuota(
        name: String,
        expectedUnit: String,
        unit: AccountUsageUnit,
    ): AccountQuota {
        val value = audio[name] as? JsonObject ?: throw AccountUsageFormatFailure()
        if (value.text("unit") != expectedUnit) throw AccountUsageFormatFailure()
        val divisor = if (unit == AccountUsageUnit.MINUTES) BigDecimal(60000) else BigDecimal.ONE

        fun amount(key: String) =
            value.amount(key).divide(divisor, 6, java.math.RoundingMode.HALF_UP).stripTrailingZeros()
        return AccountQuota(
            value.text("model"),
            audio.text("day"),
            audio.text("timezone"),
            unit,
            amount("limit"),
            amount("used"),
            amount("reserved"),
            amount("remaining"),
            audio.resetInstant(),
        )
    }
    return AccountUsageSummary(
        listOf(
            lunaQuota,
            audioQuota("asr", "milliseconds", AccountUsageUnit.MINUTES),
            audioQuota("tts", "characters", AccountUsageUnit.CHARACTERS),
        ),
        history.text("today"),
        history.objects("totals").map { AccountUsageTotal(it.text("service"), it.number("requests")) },
        history.objects("requests").take(20).map {
            AccountUsageRecord(it.text("service"), (it["status"] as? JsonPrimitive)?.longOrNull)
        },
    )
}

private fun JsonObject.text(key: String): String =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.length in 1..128 }
        ?: throw AccountUsageFormatFailure()

private fun JsonObject.number(key: String): Long =
    (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 }
        ?: throw AccountUsageFormatFailure()

private fun JsonObject.amount(key: String) = BigDecimal.valueOf(number(key))

private fun JsonObject.objects(key: String): List<JsonObject> =
    (this[key] as? JsonArray)?.map { it as? JsonObject ?: throw AccountUsageFormatFailure() }
        ?: throw AccountUsageFormatFailure()

private fun JsonObject.resetInstant(): Instant? {
    val raw = (this["resets_at"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    return try {
        Instant.parse(raw)
    } catch (_: java.time.format.DateTimeParseException) {
        null
    }
}
