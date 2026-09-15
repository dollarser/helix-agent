package com.helix.core.agent

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Pure schedule spec for a Scheduled Goal (research doc section 14 "Scheduled Tasks"): the
 * first version supports One-time, Daily and Weekly only — no cron, no complex Workflow.
 *
 * A clock-free contract: [nextFireTime] returns the next fire instant strictly after an
 * injected [now] (null when a one-time schedule is exhausted); [isRecurring] tells the Tasks
 * page whether a fired task keeps reappearing; [describe] is the human label for the Tasks
 * "Scheduled" row and the goal card. It decides *when* to fire, never *whether* a firing is
 * allowed — high-risk actions scheduled here still pass through the existing runtime approval
 * (doc section 14: "高风险动作仍要求运行时人工确认"). The app's WorkManager/AlarmManager
 * adapter applies the returned instant; the schedule fires nothing and performs no I/O.
 */
sealed interface GoalSchedule {
    /** True for the recurring kinds (a fire schedules the next one); false for [OneTime]. */
    val isRecurring: Boolean

    /** The next fire instant strictly after [now], or null when this schedule is exhausted. */
    fun nextFireTime(now: Instant): Instant?

    /** A concise, human label for the Tasks "Scheduled" row and the goal card. */
    fun describe(): String

    /** A single fire at the fixed instant [at]; exhausted once [now] reaches [at]. */
    data class OneTime(
        val at: Instant,
    ) : GoalSchedule {
        override val isRecurring: Boolean = false

        override fun nextFireTime(now: Instant): Instant? = if (now < at) at else null

        override fun describe(): String = "Once"
    }

    /** Fires every day at [timeOfDay] in [zone]. */
    data class Daily(
        val timeOfDay: LocalTime,
        val zone: ZoneId,
    ) : GoalSchedule {
        override val isRecurring: Boolean = true

        override fun nextFireTime(now: Instant): Instant {
            val nowLocal = now.atZone(zone).toLocalDateTime()
            var candidate = nowLocal.toLocalDate().atTime(timeOfDay)
            if (!candidate.isAfter(nowLocal)) candidate = candidate.plusDays(1)
            return candidate.atZone(zone).toInstant()
        }

        override fun describe(): String = "Daily at ${timeOfDay.clockLabel()}"
    }

    /** Fires every [dayOfWeek] at [timeOfDay] in [zone]. */
    data class Weekly(
        val dayOfWeek: DayOfWeek,
        val timeOfDay: LocalTime,
        val zone: ZoneId,
    ) : GoalSchedule {
        override val isRecurring: Boolean = true

        override fun nextFireTime(now: Instant): Instant {
            val nowLocal = now.atZone(zone).toLocalDateTime()
            val daysAhead = (dayOfWeek.value - nowLocal.dayOfWeek.value + 7) % 7
            var candidate = nowLocal.toLocalDate().atTime(timeOfDay).plusDays(daysAhead.toLong())
            if (!candidate.isAfter(nowLocal)) candidate = candidate.plusDays(7)
            return candidate.atZone(zone).toInstant()
        }

        override fun describe(): String = "Weekly on ${dayOfWeek.label} at ${timeOfDay.clockLabel()}"
    }
}

/** "HH:mm" for a [LocalTime]; deterministic and locale-free (no AM/PM). */
private fun LocalTime.clockLabel(): String = "%02d:%02d".format(hour, minute)

/** "Monday" from a [DayOfWeek]; the enum name, lowercased then capitalized. */
private val DayOfWeek.label: String
    get() = name.lowercase().replaceFirstChar { it.uppercase() }
