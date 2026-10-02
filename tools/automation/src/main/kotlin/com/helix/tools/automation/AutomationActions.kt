package com.helix.tools.automation

enum class AutomationTextMatch {
    EXACT,
    CONTAINS,
}

data class AutomationFindQuery(
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val className: String? = null,
    val clickable: Boolean? = null,
    val match: AutomationTextMatch = AutomationTextMatch.EXACT,
    val maxResults: Int = 20,
)

enum class AutomationFindStatus {
    FOUND,
    NOT_FOUND,
    INVALID_QUERY,
}

data class AutomationFindResult(
    val status: AutomationFindStatus,
    val nodes: List<AutomationSnapshotNode> = emptyList(),
)

enum class AutomationNodeAction {
    CLICK,
    LONG_CLICK,
    SET_TEXT,
    SET_PROGRESS,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
}

enum class AutomationGlobalAction(
    val platformId: Int,
) {
    BACK(1),
    HOME(2),
    RECENTS(3),
    NOTIFICATIONS(4),
    QUICK_SETTINGS(5),
    POWER_DIALOG(6),
    TOGGLE_SPLIT_SCREEN(7),
    LOCK_SCREEN(8),
    DISMISS_NOTIFICATION_SHADE(15),
    DPAD_UP(16),
    DPAD_DOWN(17),
    DPAD_LEFT(18),
    DPAD_RIGHT(19),
    DPAD_CENTER(20),
    ALL_APPS(14),
    HEADSET_HOOK(10),
    MENU(21),
    MEDIA_PLAY_PAUSE(22),
}

data class AutomationNodeActionRequest(
    val action: AutomationNodeAction,
    val token: String,
    val text: String? = null,
    val progress: Double? = null,
)

enum class AutomationActionStatus {
    SUCCEEDED,
    SERVICE_NOT_CONNECTED,
    NO_ACTIVE_SESSION,
    SESSION_PAUSED,
    ACTION_BUDGET_EXHAUSTED,
    TOKEN_UNKNOWN,
    STALE_TOKEN,
    TARGET_CHANGED,
    TARGET_NOT_ALLOWLISTED,
    SENSITIVE_UI,
    UNSUPPORTED_UI,
    ACTION_NOT_SUPPORTED,
    INVALID_ARGUMENT,
    ACTION_FAILED,
    ACTION_OUTCOME_UNKNOWN,
    ACTION_NOT_DISPATCHED,
}

data class AutomationActionResult(
    val status: AutomationActionStatus,
)

enum class AutomationResumeStatus {
    RESUMED,
    SERVICE_NOT_CONNECTED,
    NO_ACTIVE_SESSION,
    NOT_PAUSED,
    TARGET_NOT_ALLOWLISTED,
    SNAPSHOT_REFUSED,
    TARGET_MISMATCH,
}

/** Protected platform data is not a keyword-based business policy. */
internal fun interface SensitiveAutomationSemanticPolicy {
    fun isDenied(
        node: ObservedSnapshotNode,
        action: AutomationNodeAction?,
    ): Boolean

    companion object : SensitiveAutomationSemanticPolicy {
        override fun isDenied(
            node: ObservedSnapshotNode,
            action: AutomationNodeAction?,
        ): Boolean = node.password || node.accessibilityDataSensitive
    }
}

enum class AutomationWaitCondition { PRESENT, ABSENT, CHANGED, STABLE }

enum class AutomationWaitStatus {
    FOUND,
    ABSENT,
    CHANGED,
    STABLE,
    CANCELLED,
    INCOMPLETE_SNAPSHOT,
    TIMED_OUT,
    INVALID_ARGUMENT,
    SNAPSHOT_REFUSED,
}

data class AutomationWaitResult(
    val status: AutomationWaitStatus,
    val matches: List<AutomationSnapshotNode> = emptyList(),
    val observation: AutomationSnapshotResult? = null,
)
