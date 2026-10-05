package com.helix.extensions.mobileuse.automation.backend

import com.helix.extensions.mobileuse.automation.AutomationPoint
import com.helix.extensions.mobileuse.automation.AutomationStroke

internal data class PrivilegedTouch(
    val id: Int,
    val point: AutomationPoint,
)

internal data class PrivilegedTouchEvent(
    val time: Long,
    val action: Int,
    val touches: List<PrivilegedTouch>,
)

/** Pure event sequence, including staggered multi-touch and a separate pointer-up for each finger. */
internal fun privilegedGesturePlan(strokes: List<AutomationStroke>): List<PrivilegedTouchEvent> {
    require(strokes.isNotEmpty() && strokes.size <= 10)
    strokes.forEach {
        require(it.startMillis in 0..10_000 && it.durationMillis in 1..10_000 - it.startMillis)
        require(it.points.isNotEmpty() && it.points.size <= 512)
    }
    val end = strokes.maxOf { it.startMillis + it.durationMillis }
    val times =
        ((0L..end step 16) + strokes.flatMap { listOf(it.startMillis, it.startMillis + it.durationMillis) })
            .distinct()
            .sorted()
    val active = mutableListOf<Int>()
    val result = mutableListOf<PrivilegedTouchEvent>()

    fun event(
        time: Long,
        action: Int,
    ) {
        result.add(
            PrivilegedTouchEvent(
                time,
                action,
                active.map { id ->
                    val stroke = strokes[id]
                    val fraction = ((time - stroke.startMillis).toFloat() / stroke.durationMillis).coerceIn(0f, 1f)
                    val position = fraction * (stroke.points.size - 1)
                    val index = position.toInt()
                    val from = stroke.points[index]
                    val to = stroke.points.getOrElse(index + 1) { from }
                    val weight = position - index
                    val point =
                        AutomationPoint(
                            from.x + (to.x - from.x) * weight,
                            from.y + (to.y - from.y) * weight,
                        )
                    PrivilegedTouch(id, point)
                },
            ),
        )
    }
    for (time in times) {
        strokes.indices.filter { strokes[it].startMillis == time }.forEach { id ->
            active.add(id)
            event(time, if (active.size == 1) 0 else 5 or ((active.size - 1) shl 8))
        }
        if (active.isNotEmpty()) event(time, 2)
        active.toList().filter { strokes[it].startMillis + strokes[it].durationMillis == time }.forEach { id ->
            event(time, if (active.size == 1) 1 else 6 or (active.indexOf(id) shl 8))
            active.remove(id)
        }
    }
    return result
}
