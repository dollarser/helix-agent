package com.helix.extensions.mobileuse.automation

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Deterministic physical-pixel recipes. Dispatch still validates the generated path and original grant. */
object AutomationGesturePatterns {
    fun tap(
        at: AutomationPoint,
        duration: Long = 80,
        twice: Boolean = false,
    ): List<AutomationStroke> {
        require(duration in 1..10_000 && (!twice || duration <= 120))
        val first = AutomationStroke(listOf(at), 0, duration)
        return if (twice) listOf(first, first.copy(startMillis = duration + 80)) else listOf(first)
    }

    fun swipe(
        from: AutomationPoint,
        to: AutomationPoint,
        duration: Long,
        fingers: Int = 1,
        spacing: Float = 48f,
    ): List<AutomationStroke> {
        require(fingers in 1..10 && spacing.isFinite() && spacing > 0 && duration in 1..10_000)
        val length = hypot(to.x - from.x, to.y - from.y)
        require(length.isFinite() && length > 0)
        return List(fingers) { index ->
            val offset = (index - (fingers - 1) / 2f) * spacing
            val x = -(to.y - from.y) / length * offset
            val y = (to.x - from.x) / length * offset
            AutomationStroke(
                listOf(AutomationPoint(from.x + x, from.y + y), AutomationPoint(to.x + x, to.y + y)),
                0,
                duration,
            )
        }
    }

    fun pinch(
        center: AutomationPoint,
        span: Float,
        scale: Float,
        duration: Long,
    ): List<AutomationStroke> {
        require(span.isFinite() && span > 0 && scale.isFinite() && scale in 0.1f..10f && scale != 1f)
        require(duration in 1..10_000)
        return listOf(-1f, 1f).map { side ->
            AutomationStroke(
                listOf(
                    AutomationPoint(center.x + side * span / 2, center.y),
                    AutomationPoint(center.x + side * span * scale / 2, center.y),
                ),
                0,
                duration,
            )
        }
    }

    /** Positive degrees rotate clockwise in Android's downward-positive screen coordinates. */
    fun rotate(
        center: AutomationPoint,
        span: Float,
        degrees: Float,
        duration: Long,
    ): List<AutomationStroke> {
        require(span.isFinite() && span > 0 && degrees.isFinite() && degrees in -360f..360f && degrees != 0f)
        require(duration in 1..10_000)
        val segments = ceil(kotlin.math.abs(degrees) / 5).toInt().coerceAtLeast(1)
        return listOf(0.0, PI).map { phase ->
            val points =
                (0..segments).map { index ->
                    val angle = phase + degrees * PI / 180 * index / segments
                    AutomationPoint(
                        center.x + (cos(angle) * span / 2).toFloat(),
                        center.y + (sin(angle) * span / 2).toFloat(),
                    )
                }
            AutomationStroke(points, 0, duration)
        }
    }
}
