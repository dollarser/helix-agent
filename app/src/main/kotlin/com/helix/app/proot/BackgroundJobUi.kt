package com.helix.app.proot

/** Job identity remains separate from its producing Turn and any Goal that hides that Turn. */
data class BackgroundJobUi(
    val callId: String,
    val turnId: String,
    val sessionId: String,
    val title: String,
    val state: CommandDetailState,
    val settlementPending: Boolean,
)
