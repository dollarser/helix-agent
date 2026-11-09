package com.helix.extensions.mobileuse.automation.backend

import com.helix.extensions.mobileuse.automation.AutomationDisplayTarget

/** Observation only: settle window metadata, never app pixels or accessibility content. */
internal fun stableCoordinateTarget(
    read: () -> AutomationDisplayTarget?,
    allowed: () -> Boolean,
    now: () -> Long,
    sleep: (Long) -> Unit,
): AutomationDisplayTarget? {
    val deadline = now() + 1_000
    var previous: AutomationDisplayTarget? = null
    var unchangedSince = now()
    while (allowed() && now() < deadline) {
        val current = read()
        if (!allowed() || now() >= deadline) break
        if (current != previous) unchangedSince = now()
        if (current != null && current == previous && now() - unchangedSince >= 100) return current
        previous = current
        sleep(minOf(50, deadline - now()).coerceAtLeast(0))
    }
    return null
}
