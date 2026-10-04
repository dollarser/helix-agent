package com.helix.app.automation.shizuku

/** Pure lifecycle: a dispatched prefix keeps ownership until its remaining pointers are cancelled. */
internal class PrivilegedGestureRunner(
    private val now: () -> Long,
    private val sleep: (Long) -> Unit,
    private val allowed: () -> Boolean,
    private val ready: (Boolean) -> Boolean,
    private val inject: (PrivilegedTouchEvent, Long) -> Boolean,
) {
    @Suppress("TooGenericExceptionCaught") // An injected prefix is uncertain even when cancellation succeeds.
    fun execute(plan: List<PrivilegedTouchEvent>): String {
        var entered = false
        var touches = emptyList<PrivilegedTouch>()
        var downTime = now()
        var status = "NOT_DISPATCHED"
        try {
            require(plan.isNotEmpty())
            val start = now()
            for (step in plan) {
                waitUntil(start + step.time)
                check(allowed() && ready(entered))
                if (step.action == 0) downTime = now()
                touches = step.touches
                entered = true
                check(inject(step, downTime))
                touches = remaining(step)
            }
            status = if (allowed()) "DISPATCHED" else "OUTCOME_UNKNOWN"
        } catch (_: Exception) {
            status = if (entered) "OUTCOME_UNKNOWN" else "NOT_DISPATCHED"
        } finally {
            if (!cancel(touches, downTime)) status = "OUTCOME_UNKNOWN"
        }
        return status
    }

    private fun waitUntil(until: Long) {
        while (now() < until) {
            check(allowed())
            sleep(minOf(16, until - now()).coerceAtLeast(0))
        }
    }

    private fun remaining(step: PrivilegedTouchEvent): List<PrivilegedTouch> =
        when (step.action and 255) {
            1 -> emptyList()
            6 -> step.touches.filterIndexed { index, _ -> index != (step.action shr 8) }
            else -> step.touches
        }

    @Suppress("TooGenericExceptionCaught") // Cleanup failure must preserve an unknown physical outcome.
    private fun cancel(touches: List<PrivilegedTouch>, downTime: Long): Boolean =
        try {
            touches.isEmpty() || inject(PrivilegedTouchEvent(now(), 3, touches), downTime)
        } catch (_: Exception) {
            false
        }
}
