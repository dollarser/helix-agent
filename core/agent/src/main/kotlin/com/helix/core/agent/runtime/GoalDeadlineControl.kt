package com.helix.core.agent.runtime

/** The one live Goal clock's execution deadline. Durable allocation remains a host responsibility. */
interface GoalDeadlineControl {
    fun expiredCode(): String?

    fun checkActive()

    fun remainingExecutionMillis(): Long
}

/** Neutral deadline failure; uncertain external-effect settlement still takes precedence. */
class GoalTimeLimitException(
    val code: String,
) : RuntimeException(code)
