package com.helix.app.automation.shizuku

import kotlin.random.Random

/** One stationary short press, not separate DOWN/UP processes or a replayable gesture sequence. */
internal fun boundedTouchPressCommand(
    x: Int,
    y: Int,
    random: Random = Random.Default,
): List<String> {
    require(x >= 0 && y >= 0) { "INVALID_PRESS_POINT" }
    val durationMillis = random.nextInt(MIN_PRESS_MILLIS, MAX_PRESS_MILLIS + 1)
    return listOf(
        "/system/bin/input",
        "touchscreen",
        "-d",
        "0",
        "swipe",
        x.toString(),
        y.toString(),
        x.toString(),
        y.toString(),
        durationMillis.toString(),
    )
}

private const val MIN_PRESS_MILLIS = 60
private const val MAX_PRESS_MILLIS = 120
