package com.helix.core.model

/** Host-derived request projection, never a rewrite of a persisted tool result. */
enum class ToolImageOmission(
    val explanation: String,
) {
    OUTSIDE_RECENT_WINDOW("OUTSIDE_RECENT_WINDOW: use view_image again"),
    VISION_UNAVAILABLE("VISION_UNAVAILABLE"),
    IMAGE_BUDGET_EXCEEDED("IMAGE_BUDGET_EXCEEDED"),
    DISCLOSURE_UNAVAILABLE("PIXELS_NOT_SENT: data disclosure declined or unavailable"),
    ;

    val notice: String
        get() = "\n[TOOL IMAGE NOT PRESENTED: $explanation. Do not claim to have seen these pixels.]"
}
