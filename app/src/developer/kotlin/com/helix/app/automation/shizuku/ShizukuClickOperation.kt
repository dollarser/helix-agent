package com.helix.app.automation.shizuku

import kotlin.random.Random

/** No replays: exceptions before dispatch are refusals; anything after entry is uncertain. */
internal class ShizukuClickOperation(
    private val hierarchy: () -> String,
    private val allowed: (Int, Int, Int) -> Boolean,
    private val tap: (Int, Int) -> Boolean,
    private val random: Random = Random.Default,
) {
    @Suppress("TooGenericExceptionCaught") // XML/I/O/Binder failures share a phase-aware, never-success outcome.
    fun execute(selector: ShizukuUiSelector): String {
        var entered = false
        return try {
            check(allowed(-1, -1, -1)) { "GRANT_LOST" }
            val target = ShizukuUiTarget.resolve(hierarchy(), selector)
            check(allowed(-1, -1, -1)) { "GRANT_LOST" }
            check(ShizukuUiTarget.resolve(hierarchy(), selector) == target) { "TARGET_CHANGED" }
            val point = target.sampleClickPoint(random)
            check(allowed(point.x, point.y, target.rotation.toInt())) { "GRANT_LOST" }
            entered = true
            if (tap(point.x, point.y)) "DISPATCHED" else "OUTCOME_UNKNOWN"
        } catch (error: Exception) {
            if (entered) {
                "OUTCOME_UNKNOWN"
            } else {
                error.message?.takeIf { it in setOf("TARGET_NOT_FOUND", "TARGET_AMBIGUOUS", "TARGET_CHANGED") }
                    ?: "NOT_DISPATCHED"
            }
        }
    }
}
