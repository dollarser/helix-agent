package com.helix.tools.android

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The production [CalendarBridge] (roadmap HXA-065, doc 09 §11 / doc `overview.md` §11).
 * Context-backed: [prepareEvent] builds + holds an in-memory structured draft (no calendar access, no
 * permission needed); [commitEvent] writes the held draft to the system Calendar Provider, gated on
 * the `WRITE_CALENDAR` runtime permission.
 *
 * - Draft-first: prepare NEVER writes to the calendar; commit is the only write path and is L2 (every
 *   call is approved). doc 09: "直接 Provider 写入属于 L2".
 * - commit is refused with [CalendarCommitStatus.PERMISSION_MISSING] unless the user has granted
 *   `WRITE_CALENDAR` — the stable signal (never a fake success / empty result).
 * - The draft store is bounded ([MAX_CALENDAR_DRAFTS]); a commit consumes (removes) its draft, so a
 *   draft can be committed at most once.
 *
 * [permissionProbe] and [writer] are injectable seams so the instrumented test proves the real gating +
 * draft-management logic without needing `WRITE_CALENDAR` granted (which an instrumented test cannot
 * self-grant); the production defaults below are what the app container uses. The port never throws for
 * a system condition; a genuine failure is a stable ERROR outcome.
 */
class CalendarBridgeImpl(
    private val context: Context,
    private val permissionProbe: CalendarPermissionProbe = RealCalendarPermissionProbe(context),
    private val writer: CalendarWriter = ContextCalendarWriter(context),
) : CalendarBridge {
    private val drafts = ConcurrentHashMap<String, CalendarEventRequest>()

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount") // stable outcomes, never a crash
    override fun prepareEvent(request: CalendarEventRequest): CalendarPrepareOutcome {
        if (request.title.isBlank()) return invalid("title is blank")
        if (request.endEpochMillis <= request.startEpochMillis) return invalid("end must be after start")
        if (request.endEpochMillis - request.startEpochMillis > MAX_EVENT_DURATION_MILLIS) {
            return invalid("event duration exceeds the bound")
        }
        val draftId = UUID.randomUUID().toString()
        drafts[draftId] = request
        evictIfOverBound()
        return CalendarPrepareOutcome(
            status = CalendarPrepareStatus.PREPARED,
            draftId = draftId,
            title = request.title,
            startEpochMillis = request.startEpochMillis,
            endEpochMillis = request.endEpochMillis,
            location = request.location,
            notes = request.notes,
            timeZoneId = request.timeZoneId.ifBlank { TimeZone.getDefault().id },
            reason = "",
        )
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount") // stable outcomes, never a crash
    override fun commitEvent(draftId: String): CalendarCommitOutcome {
        if (!permissionProbe.hasWriteCalendar()) {
            return CalendarCommitOutcome(
                status = CalendarCommitStatus.PERMISSION_MISSING,
                draftId = draftId,
                eventId = "",
                reason = "permission-missing",
            )
        }
        val request =
            drafts.remove(draftId)
                ?: return CalendarCommitOutcome(
                    status = CalendarCommitStatus.DRAFT_NOT_FOUND,
                    draftId = draftId,
                    eventId = "",
                    reason = "draft-not-found",
                )
        return try {
            when (val w = writer.write(request)) {
                is CalendarWriteResult.Written -> {
                    CalendarCommitOutcome(CalendarCommitStatus.COMMITTED, draftId, w.eventId, "")
                }

                CalendarWriteResult.NoHandler -> {
                    CalendarCommitOutcome(CalendarCommitStatus.NO_HANDLER, draftId, "", "no-handler")
                }

                CalendarWriteResult.Error -> {
                    CalendarCommitOutcome(CalendarCommitStatus.ERROR, draftId, "", "calendar write failed")
                }
            }
        } catch (e: Exception) {
            CalendarCommitOutcome(CalendarCommitStatus.ERROR, draftId, "", "calendar write failed")
        }
    }

    private fun invalid(reason: String): CalendarPrepareOutcome =
        CalendarPrepareOutcome(
            status = CalendarPrepareStatus.INVALID,
            draftId = "",
            title = "",
            startEpochMillis = 0,
            endEpochMillis = 0,
            location = "",
            notes = "",
            timeZoneId = "",
            reason = reason,
        )

    // The draft store is bounded; drafts are ephemeral and unordered, so which entry drops when over
    // the bound is not observable to the model.
    private fun evictIfOverBound() {
        while (drafts.size > MAX_CALENDAR_DRAFTS) {
            val first = drafts.keys.firstOrNull() ?: break
            drafts.remove(first)
        }
    }
}

/**
 * Whether the user has granted the `WRITE_CALENDAR` runtime permission (the commit gate, doc 09 §11).
 * A seam so the device test can assert both states deterministically; the production default reads the
 * live permission.
 */
interface CalendarPermissionProbe {
    fun hasWriteCalendar(): Boolean
}

/** The production [CalendarPermissionProbe]: a direct, always-permitted permission check. */
class RealCalendarPermissionProbe(
    private val context: Context,
) : CalendarPermissionProbe {
    override fun hasWriteCalendar(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED
}

/** The result of a [CalendarWriter.write]: the new event id, no writable calendar, or a failure. */
sealed class CalendarWriteResult {
    data class Written(
        val eventId: String,
    ) : CalendarWriteResult()

    data object NoHandler : CalendarWriteResult()

    data object Error : CalendarWriteResult()
}

/**
 * The (Android-backed) calendar write. A seam so the device test can record the request instead of
 * writing; the production default performs the real [CalendarContract.Events] insert.
 */
interface CalendarWriter {
    fun write(request: CalendarEventRequest): CalendarWriteResult
}

/**
 * The production [CalendarWriter]: inserts one [CalendarContract.Events] row into the first writable
 * calendar the provider exposes. Returns [CalendarWriteResult.NoHandler] when no writable calendar
 * exists and [CalendarWriteResult.Error] on a provider failure — never throws (the bridge maps a throw
 * to ERROR too). Requires the caller to have already passed the `WRITE_CALENDAR` gate.
 */
class ContextCalendarWriter(
    private val context: Context,
) : CalendarWriter {
    @Suppress("TooGenericExceptionCaught", "SwallowedException", "ReturnCount") // stable outcomes, never a crash
    override fun write(request: CalendarEventRequest): CalendarWriteResult {
        return try {
            val calendarId = findWritableCalendarId() ?: return CalendarWriteResult.NoHandler
            val values =
                ContentValues().apply {
                    put(CalendarContract.Events.TITLE, request.title)
                    put(CalendarContract.Events.DTSTART, request.startEpochMillis)
                    put(CalendarContract.Events.DTEND, request.endEpochMillis)
                    if (request.location.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, request.location)
                    if (request.notes.isNotBlank()) put(CalendarContract.Events.DESCRIPTION, request.notes)
                    put(CalendarContract.Events.CALENDAR_ID, calendarId)
                }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri == null) return CalendarWriteResult.Error
            CalendarWriteResult.Written(uri.lastPathSegment.orEmpty())
        } catch (e: Exception) {
            CalendarWriteResult.Error
        }
    }

    /**
     * The id of the first calendar the provider exposes with write access
     * ([CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR] or higher), or null when none exists. The caller
     * maps null to [CalendarWriteResult.NoHandler].
     */
    private fun findWritableCalendarId(): Long? =
        context.contentResolver
            .query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
                null,
                null,
                null,
            )?.use { cursors ->
                var found: Long? = null
                while (cursors.moveToNext()) {
                    if (cursors.getInt(1) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                        found = cursors.getLong(0)
                        break
                    }
                }
                found
            }
}
