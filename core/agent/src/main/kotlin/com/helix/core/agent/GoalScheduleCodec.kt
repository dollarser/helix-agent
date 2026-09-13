package com.helix.core.agent

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The canonical string form of a [GoalSchedule] for durable storage — one TEXT column on the Room
 * goal row (doc section 14). The shapes are unambiguous and round-trip exactly:
 *  - one-time: `once:<ISO-8601 instant>`
 *  - daily:    `daily:<time>[<zone id>]`
 *  - weekly:   `weekly:<DAY_OF_WEEK>:<time>[<zone id>]`
 *
 * [encode] is deterministic and locale-free; [decode] is its exact inverse and fails closed with
 * [IllegalArgumentException] on anything malformed, so a corrupt column can never silently become a
 * wrong (or missing) schedule. The user-facing label is [GoalSchedule.describe], not this — storage
 * is lossless, so a time keeps its seconds and nanos across a round-trip.
 */
object GoalScheduleCodec {
    fun encode(schedule: GoalSchedule): String =
        when (schedule) {
            is GoalSchedule.OneTime -> {
                "once:${schedule.at}"
            }

            is GoalSchedule.Daily -> {
                "daily:${schedule.timeOfDay}[${schedule.zone}]"
            }

            is GoalSchedule.Weekly -> {
                "weekly:${schedule.dayOfWeek.name}:${schedule.timeOfDay}[${schedule.zone}]"
            }
        }

    /** The exact inverse of [encode]; throws [IllegalArgumentException] on malformed input. */
    fun decode(text: String): GoalSchedule {
        try {
            return decodeUnchecked(text)
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: java.time.DateTimeException) {
            throw IllegalArgumentException("malformed schedule: $text", e)
        }
    }

    private fun decodeUnchecked(text: String): GoalSchedule {
        val colon = text.indexOf(':')
        require(colon > 0) { "malformed schedule: $text" }
        val kind = text.substring(0, colon)
        val rest = text.substring(colon + 1)
        return when (kind) {
            "once" -> {
                GoalSchedule.OneTime(Instant.parse(rest))
            }

            "daily" -> {
                val (time, zone) = timeAndZone(rest)
                GoalSchedule.Daily(time, zone)
            }

            "weekly" -> {
                val dayColon = rest.indexOf(':')
                require(dayColon > 0) { "malformed weekly schedule: $text" }
                val day = DayOfWeek.valueOf(rest.substring(0, dayColon))
                val (time, zone) = timeAndZone(rest.substring(dayColon + 1))
                GoalSchedule.Weekly(day, time, zone)
            }

            else -> {
                throw IllegalArgumentException("unknown schedule kind: $kind")
            }
        }
    }

    /** Splits `<time>[<zone id>]` (time `HH:MM`, `HH:MM:SS`, or `HH:MM:SS.fff`) into (time, zone). */
    private fun timeAndZone(text: String): Pair<LocalTime, ZoneId> {
        val lb = text.lastIndexOf('[')
        val rb = text.lastIndexOf(']')
        require(lb > 0 && rb == text.length - 1 && rb > lb) { "malformed schedule zone: $text" }
        val time = LocalTime.parse(text.substring(0, lb))
        val zone = ZoneId.of(text.substring(lb + 1, rb))
        return time to zone
    }
}
