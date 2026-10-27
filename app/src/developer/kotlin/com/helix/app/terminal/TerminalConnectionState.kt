package com.helix.app.terminal

internal val ManualTerminal.State.canConnect: Boolean
    get() = phase != "UNKNOWN" && !canSettle

/** Initial selection prefers a usable shell, without changing explicit user selection or creating one. */
internal fun List<ManualTerminal.State>.preferredTerminal(): ManualTerminal.State? =
    firstOrNull { it.phase == "RUNNING" && it.canConnect } ?: firstOrNull { it.canConnect }
