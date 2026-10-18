package com.helix.app.terminal

internal val ManualTerminal.State.canConnect: Boolean
    get() = phase != "UNKNOWN" && !canSettle
