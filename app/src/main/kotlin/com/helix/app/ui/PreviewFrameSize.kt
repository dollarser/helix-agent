package com.helix.app.ui

/** Hysteresis prevents tiny layout/rounding changes from repeatedly switching encoding tiers. */
internal fun previewFrameWidth(
    pixels: Float,
    current: Int,
): Int =
    when {
        pixels > 760f -> 720
        pixels < 340f -> 240
        current == 720 && pixels >= 680f -> 720
        current == 240 && pixels <= 380f -> 240
        else -> 480
    }
