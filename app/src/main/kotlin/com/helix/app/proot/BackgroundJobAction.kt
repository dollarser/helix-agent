package com.helix.app.proot

/** Closed user actions on an existing Job. There is deliberately no launch or replacement target. */
enum class BackgroundJobAction { QUERY, STOP_WAITING, CANCEL, COLLECT }

enum class BackgroundJobActionOutcome {
    ACTIVE,
    STOP_REQUESTED,
    WAIT_STOPPED,
    NOT_WAITING,
    TERMINAL_PENDING,
    SETTLED,
    MISSING_RESULT_SETTLED,
    BUSY,
    REVIEW_REQUIRED,
    REBOOT_REQUIRED,
    FAILED,
}

data class BackgroundJobActionUi(
    val callId: String,
    val action: BackgroundJobAction,
    val busy: Boolean,
    val outcome: BackgroundJobActionOutcome? = null,
    val queryBusy: Boolean = busy && action == BackgroundJobAction.QUERY,
    val controlBusy: Boolean = busy && action != BackgroundJobAction.QUERY,
) {
    /** UI admission only; actual original-identity authorization remains in the application service. */
    fun allows(next: BackgroundJobAction): Boolean = if (next == BackgroundJobAction.QUERY) !queryBusy else !controlBusy
}
