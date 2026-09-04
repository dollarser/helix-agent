package com.helix.tools.android

/**
 * The synchronous port the `calendar.prepare_event` / `calendar.commit_event` tools (HXA-065)
 * execute against.
 *
 * Production is [CalendarBridgeImpl] (Context-backed, in this module); unit tests inject a fake. The
 * port is PURE JVM (no Android types in the interface or the outcomes) and returns small, bounded,
 * no-null view objects; the tool maps one outcome to a Completed / Failed ToolExecutorResult
 * fail-closed. The port never throws for a page or system condition (only for a genuine programming
 * error).
 *
 * Draft-first (roadmap HXA-065: "Calendar 先草稿再 commit"; doc `architecture/overview.md` §11):
 * [prepareEvent] ONLY builds + holds a structured draft — it never touches the calendar and needs no
 * permission — so the model and the user can review title/time/timezone/location/notes before any
 * write. [commitEvent] is the single write path (a direct Calendar Provider write, L2 — every call is
 * approved) and is refused with [CalendarCommitStatus.PERMISSION_MISSING] unless the user has granted
 * the `WRITE_CALENDAR` runtime permission.
 */
interface CalendarBridge {
    /**
     * Builds and holds a structured calendar-event draft. Returns a [CalendarPrepareOutcome] with a
     * [CalendarPrepareOutcome.draftId] referencing the held draft (for a later [commitEvent]). This
     * never writes to the calendar and needs no permission.
     */
    fun prepareEvent(request: CalendarEventRequest): CalendarPrepareOutcome

    /**
     * Commits the held draft [draftId] to the system Calendar Provider. Refused with
     * [CalendarCommitStatus.PERMISSION_MISSING] unless the user has granted `WRITE_CALENDAR`, and with
     * [CalendarCommitStatus.DRAFT_NOT_FOUND] if [draftId] is not a held draft. A successful commit
     * consumes the draft (it can be committed at most once).
     */
    fun commitEvent(draftId: String): CalendarCommitOutcome
}

/** The structured fields a calendar event is built from (all bounded by the tool's input schema). */
data class CalendarEventRequest(
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val location: String,
    val notes: String,
    val timeZoneId: String,
)

enum class CalendarPrepareStatus { PREPARED, INVALID, ERROR }

/**
 * Outcome of [CalendarBridge.prepareEvent]. On PREPARED, [draftId] references the held draft and the
 * structured fields echo the request (for the UI to show title/time/timezone/location/notes) with
 * [timeZoneId] resolved (a blank request value becomes the device default zone). On INVALID the fields
 * are empty/0 and [reason] names the problem (e.g. end not after start). [reason] is "" on PREPARED.
 */
data class CalendarPrepareOutcome(
    val status: CalendarPrepareStatus,
    val draftId: String,
    val title: String,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val location: String,
    val notes: String,
    val timeZoneId: String,
    val reason: String,
)

enum class CalendarCommitStatus { COMMITTED, PERMISSION_MISSING, DRAFT_NOT_FOUND, NO_HANDLER, ERROR }

/**
 * Outcome of [CalendarBridge.commitEvent]. On COMMITTED, [eventId] is the new calendar event id (a
 * stable note if the provider does not expose one). On PERMISSION_MISSING, `WRITE_CALENDAR` is not
 * granted — the stable signal (never a fake success). On DRAFT_NOT_FOUND, [draftId] is not a held
 * draft (unknown or already committed). [reason] is a stable note / error; "" on a commit.
 */
data class CalendarCommitOutcome(
    val status: CalendarCommitStatus,
    val draftId: String,
    val eventId: String,
    val reason: String,
)

// Pure-JVM business bounds, shared by the port impl and the unit tests.
internal const val MAX_EVENT_DURATION_MILLIS: Long = 30L * 24 * 86_400_000
internal const val MAX_CALENDAR_DRAFTS: Int = 32
