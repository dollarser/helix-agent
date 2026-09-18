package com.helix.app.proot

/** Closed user actions on an existing Job. There is deliberately no launch or replacement target. */
enum class BackgroundJobAction { QUERY, CANCEL, COLLECT }

enum class BackgroundJobActionOutcome { ACTIVE, STOP_REQUESTED, TERMINAL_PENDING, SETTLED, BUSY, FAILED }

data class BackgroundJobActionUi(
    val callId: String,
    val action: BackgroundJobAction,
    val busy: Boolean,
    val outcome: BackgroundJobActionOutcome? = null,
)
