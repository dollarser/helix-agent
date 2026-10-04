package com.helix.app.automation.shizuku

import kotlin.random.Random

internal data class BoundedClickPoint(
    val x: Int,
    val y: Int,
)

/** Sample once near the observed center; never widen the target or retry another point. */
internal fun ShizukuUiTarget.sampleClickPoint(random: Random): BoundedClickPoint {
    fun offset(size: Int): Int {
        val radius = minOf(size / SIZE_DIVISOR, MAX_OFFSET_PIXELS)
        return if (radius == 0) 0 else random.nextInt(-radius, radius + 1)
    }
    return BoundedClickPoint(x + offset(right - left), y + offset(bottom - top))
}

private const val SIZE_DIVISOR = 20
private const val MAX_OFFSET_PIXELS = 4
