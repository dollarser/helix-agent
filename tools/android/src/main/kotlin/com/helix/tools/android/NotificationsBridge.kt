package com.helix.tools.android

/**
 * The synchronous port the `notifications.query` tool (HXA-065) executes against.
 *
 * Production is [NotificationsBridgeImpl] (Context-backed, in this module); unit tests inject a fake.
 * The port is PURE JVM (no Android types in the interface or the outcomes) and returns small, bounded,
 * no-null view objects; the tool maps one outcome to a Completed / Failed ToolExecutorResult
 * fail-closed. A permission refusal is a stable `permission-missing` status, NEVER a fake success and
 * NEVER an empty list pretending to be a successful read — doc `architecture/overview.md` §11:
 * "NotificationListenerService 层返回 PermissionMissing，不能返回空列表冒充成功".
 *
 * The security controls live in the impl, not the port (roadmap HXA-065, doc 09 §11): the query is
 * refused with [NotificationQueryStatus.PERMISSION_MISSING] unless the user has enabled this app's
 * system Notification Listener, and the returned snapshot is bounded by the caller's app allowlist +
 * time window (at most 24h). The port never throws for a page or system condition (only for a genuine
 * programming error).
 */
interface NotificationsBridge {
    /**
     * Reads the active notifications posted by [NotificationQueryRequest.allowedPackages] within the
     * caller's `[sinceEpochMillis, untilEpochMillis]` window. Refused with
     * [NotificationQueryStatus.PERMISSION_MISSING] unless the user has enabled this app's system
     * Notification Listener (doc 09 §11).
     */
    fun query(request: NotificationQueryRequest): NotificationQueryOutcome
}

/**
 * A pure-JVM view of one active notification, produced by the (Android-backed) source and consumed by
 * the pure-JVM [filterNotifications]. No Android types, so the filtering is unit-testable on the JVM
 * in isolation.
 */
data class RawNotification(
    val packageName: String,
    val title: String,
    val text: String,
    val postedEpochMillis: Long,
)

/** The caller-scoped query: the app allowlist + a bounded time window (the tool validates the window). */
data class NotificationQueryRequest(
    val allowedPackages: List<String>,
    val sinceEpochMillis: Long,
    val untilEpochMillis: Long,
)

enum class NotificationQueryStatus { QUERIED, PERMISSION_MISSING, ERROR }

/**
 * One bounded notification entry in the query result. [title] / [text] are capped at
 * [MAX_NOTIFICATION_TITLE] / [MAX_NOTIFICATION_TEXT]; notification content is other-app data, so it is
 * bounded on the way out.
 */
data class NotificationEntry(
    val packageName: String,
    val title: String,
    val text: String,
    val postedEpochMillis: Long,
)

/**
 * Outcome of [NotificationsBridge.query]. On [NotificationQueryStatus.QUERIED], [entries] holds the
 * bounded, allowlist- and window-filtered snapshot (newest first) and [excludedCount] the number of
 * in-window notifications filtered OUT by the app allowlist (so the model knows the window had more
 * activity than it was allowed to see). On PERMISSION_MISSING, [entries] is empty and [reason] is
 * `permission-missing` — the explicit, stable "the listener is not enabled" signal (NOT an empty list
 * masquerading as a successful read). [reason] is "" for a successful query and an error note for ERROR.
 */
data class NotificationQueryOutcome(
    val status: NotificationQueryStatus,
    val entries: List<NotificationEntry>,
    val excludedCount: Int,
    val reason: String,
)

// Pure-JVM output bounds, shared by the `notifications.query` output schema (tools) and the filter
// below so the emitted entries are always schema-valid.
internal const val MAX_NOTIFICATION_ENTRIES: Int = 20
internal const val MAX_NOTIFICATION_TITLE: Int = 200
internal const val MAX_NOTIFICATION_TEXT: Int = 500

/** The result of [filterNotifications]: the bounded [entries] (newest first) + how many in-window
 * notifications the allowlist dropped. */
internal data class FilteredNotifications(
    val entries: List<NotificationEntry>,
    val excluded: Int,
)

/**
 * Pure-JVM filter for [NotificationsBridge.query]: keeps notifications whose package is in
 * [allowedPackages] AND whose post time falls within `[sinceEpochMillis, untilEpochMillis]`, orders
 * them newest-first, caps the result at [maxEntries], and bounds each title/text. [FilteredNotifications.excluded]
 * is the count of in-window notifications dropped by the allowlist. An empty/invalid window
 * (until < since) yields no entries and zero excluded.
 */
internal fun filterNotifications(
    raw: List<RawNotification>,
    allowedPackages: Set<String>,
    sinceEpochMillis: Long,
    untilEpochMillis: Long,
    maxEntries: Int = MAX_NOTIFICATION_ENTRIES,
): FilteredNotifications {
    if (untilEpochMillis < sinceEpochMillis) return FilteredNotifications(emptyList(), 0)
    val inWindow = raw.filter { it.postedEpochMillis in sinceEpochMillis..untilEpochMillis }
    val allowed = inWindow.filter { it.packageName in allowedPackages }
    val excluded = inWindow.size - allowed.size
    val entries =
        allowed
            .sortedByDescending { it.postedEpochMillis }
            .take(maxEntries)
            .map {
                NotificationEntry(
                    packageName = it.packageName,
                    title = it.title.take(MAX_NOTIFICATION_TITLE),
                    text = it.text.take(MAX_NOTIFICATION_TEXT),
                    postedEpochMillis = it.postedEpochMillis,
                )
            }
    return FilteredNotifications(entries, excluded)
}
