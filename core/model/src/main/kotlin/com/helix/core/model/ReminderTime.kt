package com.helix.core.model

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class ReminderRepeat { ONCE, DAILY, WEEKLY, MONTHLY }

/** Fixed IANA zone and calendar anchor. DST gaps move forward; overlaps use the earlier offset. */
data class ReminderTime(
    val local: LocalDateTime,
    val zone: String,
    val repeat: ReminderRepeat,
) {
    private fun occurrence(index: Long): Long {
        val date =
            when (repeat) {
                ReminderRepeat.ONCE -> local
                ReminderRepeat.DAILY -> local.plusDays(index)
                ReminderRepeat.WEEKLY -> local.plusWeeks(index)
                ReminderRepeat.MONTHLY -> local.plusMonths(index)
            }
        return date.atZone(ZoneId.of(zone)).toInstant().toEpochMilli()
    }

    fun first(): Long = occurrence(0)

    /** First anchored occurrence strictly after [after]; no replay of missed intervals. */
    fun next(after: Long): Long? {
        if (after < first() || repeat == ReminderRepeat.ONCE) return first().takeIf { after < it }
        val at =
            java.time.Instant
                .ofEpochMilli(after)
                .atZone(ZoneId.of(zone))
                .toLocalDateTime()
        val distance =
            when (repeat) {
                ReminderRepeat.DAILY -> {
                    ChronoUnit.DAYS.between(local.toLocalDate(), at.toLocalDate())
                }

                ReminderRepeat.WEEKLY -> {
                    ChronoUnit.DAYS.between(local.toLocalDate(), at.toLocalDate()) / 7
                }

                ReminderRepeat.MONTHLY -> {
                    ChronoUnit.MONTHS.between(
                        java.time.YearMonth.from(local),
                        java.time.YearMonth.from(at),
                    )
                }

                ReminderRepeat.ONCE -> {
                    error("Handled above")
                }
            }
        var index = maxOf(0, distance)
        while (occurrence(index) <= after) index++
        return occurrence(index)
    }
}
