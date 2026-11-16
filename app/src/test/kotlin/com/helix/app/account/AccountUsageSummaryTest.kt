package com.helix.app.account

import com.helix.app.ui.accountQuotaResetTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.Instant

class AccountUsageSummaryTest {
    private fun documents(
        unit: String = "milliseconds",
        remaining: Long = 400,
    ) = AccountUsageDocuments(
        Json
            .parseToJsonElement(
                """{"model":"luna","month":"2026-11","timezone":"Asia/Shanghai",
            "monthly_tokens":1000,"used_tokens":600,"reserved_tokens":100,"remaining_tokens":$remaining}""",
            ).jsonObject,
        Json
            .parseToJsonElement(
                """{"day":"2026-11-15","timezone":"Asia/Shanghai",
            "asr":{"model":"transcribe","unit":"$unit","limit":1800000,"used":90000,"reserved":30000,"remaining":1710000},
            "tts":{"model":"speech","unit":"characters","limit":12345,"used":300,"reserved":100,"remaining":12045}}""",
            ).jsonObject,
        Json
            .parseToJsonElement(
                """{"today":"2026-11-15","totals":[{"service":"speech","requests":2}],
            "requests":[{"service":"speech","status":200,"client_ip":"private","user_id":42}]}""",
            ).jsonObject,
    )

    @Test fun usesServerRemainingWithoutSubtractingReservationsAgain() {
        val result = documents(remaining = 350).summary()
        assertEquals("350", result.quotas[0].remaining.toPlainString())
        assertEquals("600", result.quotas[0].used.toPlainString())
        assertEquals("100", result.quotas[0].reserved.toPlainString())
    }

    @Test fun convertsMillisecondsToMinutesAndPreservesConfiguredLimits() {
        val result = documents().summary()
        assertEquals("1.5", result.quotas[1].used.toPlainString())
        assertEquals("0.5", result.quotas[1].reserved.toPlainString())
        assertEquals("28.5", result.quotas[1].remaining.toPlainString())
        assertEquals("12345", result.quotas[2].limit.toPlainString())
        assertEquals("Asia/Shanghai", result.quotas[1].timezone)
    }

    @Test fun rejectsUnexpectedUnitsRatherThanMislabelingAmounts() {
        assertThrows(AccountUsageFormatFailure::class.java) { documents(unit = "seconds").summary() }
    }

    @Test fun historyContainsOnlyExplicitPresentationFields() {
        val result = documents().summary()
        assertEquals(listOf(AccountUsageTotal("speech", 2)), result.totals)
        assertEquals(listOf(AccountUsageRecord("speech", 200)), result.records)
    }

    @Test fun resetInstantsComeFromServerAndAudioSharesItsTopLevelReset() {
        val source = documents()
        val lunaReset = "2026-11-30T16:00:00Z"
        val audioReset = "2026-11-15T16:00:00Z"
        val result =
            AccountUsageDocuments(
                JsonObject(source.luna + ("resets_at" to JsonPrimitive(lunaReset))),
                JsonObject(source.audio + ("resets_at" to JsonPrimitive(audioReset))),
                source.history,
            ).summary()
        assertEquals(Instant.parse(lunaReset), result.quotas[0].resetAt)
        assertEquals(Instant.parse(audioReset), result.quotas[1].resetAt)
        assertEquals(result.quotas[1].resetAt, result.quotas[2].resetAt)
        assertEquals("2026-12-01 00:00:00", accountQuotaResetTime(result.quotas[0]))
        assertEquals("2026-11-16 00:00:00", accountQuotaResetTime(result.quotas[1]))
    }

    @Test fun missingOrMalformedResetIsNotInventedFromPeriod() {
        val source = documents()
        assertEquals(null, source.summary().quotas[0].resetAt)
        val result =
            AccountUsageDocuments(
                JsonObject(source.luna + ("resets_at" to JsonPrimitive("tomorrow"))),
                source.audio,
                source.history,
            ).summary()
        assertEquals(null, result.quotas[0].resetAt)
        assertEquals(null, accountQuotaResetTime(result.quotas[0]))
    }

    @Test fun resetDisplayUsesServerZoneAndDoesNotGuessAnInvalidZone() {
        val quota =
            documents().summary().quotas[0].copy(
                resetAt = Instant.parse("2026-11-30T16:00:00Z"),
                timezone = "UTC",
            )
        assertEquals("2026-11-30 16:00:00", accountQuotaResetTime(quota))
        assertEquals(null, accountQuotaResetTime(quota.copy(timezone = "invalid-zone")))
    }
}
