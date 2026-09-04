package com.helix.tools.android

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The on-device verification gate for HXA-065 (verification-matrix row
 * `:tools:android:connectedDebugAndroidTest`).
 *
 * What the device proves here, against the PRODUCTION [NotificationsBridgeImpl] /
 * [CalendarBridgeImpl]:
 * - the permission gate runs FIRST: with the listener/permission probe OFF, `notifications.query`
 *   returns [NotificationQueryStatus.PERMISSION_MISSING] (never a fake empty success) and
 *   `calendar.commit_event` returns [CalendarCommitStatus.PERMISSION_MISSING] (never reaching the
 *   writer) — doc 09 §11 / overview.md §11;
 * - with the probe ON, [NotificationsBridgeImpl.query] runs the REAL production filter
 *   (allowlist + time-window + newest-first + bounding) over the injected snapshot;
 * - `calendar.prepare_event` builds + holds a structured draft WITHOUT touching the calendar or
 *   needing any permission; `calendar.commit_event` is the only write path and CONSUMES its draft
 *   (a second commit of the same id is DRAFT_NOT_FOUND);
 * - the manifest-declared [HelixNotificationListenerService] is present in the merged manifest (the
 *   "Notification access" toggle the user enables).
 *
 * The [NotificationPermissionProbe] / [CalendarPermissionProbe] / [NotificationSource] / [CalendarWriter]
 * are injected so the test proves the real gating + filtering + draft-management logic WITHOUT
 * depending on flaky live state: the system Notification Listener is user-gated and the
 * `WRITE_CALENDAR` runtime permission cannot be self-granted from an instrumented test. The production
 * defaults (real `Settings.Secure` / `checkSelfPermission` probes, real listener snapshot, real
 * Calendar Provider write) are what the app container uses; the two real permission probes are still
 * exercised on-device as no-throw smoke tests.
 */
@RunWith(AndroidJUnit4::class)
class NotificationsCalendarBridgeDeviceTest {
    private lateinit var context: android.content.Context

    private lateinit var notifProbe: ToggleNotifProbe
    private lateinit var notifSource: FakeNotifSource
    private lateinit var notifBridge: NotificationsBridgeImpl

    private lateinit var calProbe: ToggleCalProbe
    private lateinit var writer: RecordingWriter
    private lateinit var cal: CalendarBridgeImpl

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        notifProbe = ToggleNotifProbe()
        notifSource = FakeNotifSource()
        notifBridge = NotificationsBridgeImpl(context, notifProbe, notifSource)
        calProbe = ToggleCalProbe()
        writer = RecordingWriter()
        cal = CalendarBridgeImpl(context, calProbe, writer)
    }

    // ── notifications.query: gate first, then the real production filter ──────────────────

    @Test
    fun queryIsPermissionMissingWhenTheListenerIsNotEnabled() {
        notifProbe.enabled = false
        // A non-empty snapshot proves the gate short-circuits BEFORE the read: the result must be the
        // explicit permission-missing signal, not an (empty) successful read.
        notifSource.raws = listOf(RawNotification("com.app.a", "t", "", 100L))
        val out = notifBridge.query(NotificationQueryRequest(listOf("com.app.a"), 0L, 500L))
        assertEquals(NotificationQueryStatus.PERMISSION_MISSING, out.status)
        assertTrue(out.entries.isEmpty())
        assertEquals("permission-missing", out.reason)
    }

    @Test
    fun queryRunsTheRealProductionFilterWhenTheListenerIsEnabled() {
        notifProbe.enabled = true
        notifSource.raws =
            listOf(
                RawNotification("com.app.a", "a-new", "", 300L),
                RawNotification("com.app.a", "a-old", "", 100L),
                RawNotification("com.app.c", "c-excluded", "", 250L),
            )
        val out = notifBridge.query(NotificationQueryRequest(listOf("com.app.a"), 0L, 500L))
        assertEquals(NotificationQueryStatus.QUERIED, out.status)
        assertEquals(listOf("com.app.a", "com.app.a"), out.entries.map { it.packageName })
        assertEquals(listOf(300L, 100L), out.entries.map { it.postedEpochMillis })
        assertEquals(1, out.excludedCount)
        assertEquals("", out.reason)
    }

    @Test
    fun theNotificationListenerServiceIsDeclaredInTheMergedManifest() {
        val pm = context.packageManager
        val services =
            pm.queryIntentServices(Intent("android.service.notification.NotificationListenerService"), 0)
        val found =
            services.any {
                it.serviceInfo.packageName == context.packageName &&
                    it.serviceInfo.name == "com.helix.tools.android.HelixNotificationListenerService"
            }
        assertTrue("the NotificationListenerService must be declared in the merged manifest", found)
    }

    // ── calendar.prepare_event: draft only, no write, no permission ───────────────────────

    @Test
    fun prepareBuildsAHeldDraftWithoutTouchingTheCalendar() {
        calProbe.granted = false // prepare needs no permission
        val out = cal.prepareEvent(CalendarEventRequest("Standup", 1000L, 2000L, "Room", "notes", "Asia/Shanghai"))
        assertEquals(CalendarPrepareStatus.PREPARED, out.status)
        assertTrue(out.draftId.isNotBlank())
        assertEquals("Standup", out.title)
        assertEquals("Room", out.location)
        assertEquals("Asia/Shanghai", out.timeZoneId)
        assertEquals("", out.reason)
        assertNull("prepare must never reach the writer", writer.last)
    }

    @Test
    fun prepareResolvesABlankTimeZoneToTheDeviceDefault() {
        val out = cal.prepareEvent(CalendarEventRequest("E", 1000L, 2000L, "", "", ""))
        assertEquals(CalendarPrepareStatus.PREPARED, out.status)
        assertTrue("a blank timeZoneId must be resolved to the device default zone", out.timeZoneId.isNotBlank())
    }

    @Test
    fun prepareInvalidTimesIsInvalidWithoutWriting() {
        val out = cal.prepareEvent(CalendarEventRequest("E", 2000L, 1000L, "", "", ""))
        assertEquals(CalendarPrepareStatus.INVALID, out.status)
        assertEquals("end must be after start", out.reason)
        assertNull(writer.last)
    }

    // ── calendar.commit_event: the write path, gated + consumes its draft ──────────────────

    @Test
    fun commitIsPermissionMissingWhenWriteCalendarIsNotGranted() {
        calProbe.granted = false
        val prep = cal.prepareEvent(CalendarEventRequest("E", 1000L, 2000L, "", "", ""))
        val out = cal.commitEvent(prep.draftId)
        assertEquals(CalendarCommitStatus.PERMISSION_MISSING, out.status)
        assertEquals("permission-missing", out.reason)
        assertNull("the writer must not be reached when the permission is off", writer.last)
    }

    @Test
    fun commitIsDraftNotFoundForAnUnknownDraft() {
        calProbe.granted = true
        val out = cal.commitEvent("no-such-draft")
        assertEquals(CalendarCommitStatus.DRAFT_NOT_FOUND, out.status)
        assertEquals("draft-not-found", out.reason)
        assertNull(writer.last)
    }

    @Test
    fun commitConsumesTheDraftSoItCannotBeCommittedTwice() {
        calProbe.granted = true
        val prep = cal.prepareEvent(CalendarEventRequest("Standup", 1000L, 2000L, "Room", "notes", "Asia/Shanghai"))
        val first = cal.commitEvent(prep.draftId)
        assertEquals(CalendarCommitStatus.COMMITTED, first.status)
        assertEquals("evt-1", first.eventId)
        assertEquals("Standup", writer.last?.title)
        assertEquals("Room", writer.last?.location)
        val second = cal.commitEvent(prep.draftId)
        assertEquals(CalendarCommitStatus.DRAFT_NOT_FOUND, second.status)
    }

    // ── the real production permission probes run on-device without throwing ──────────────

    @Test
    fun theRealCalendarPermissionProbeReturnsAStableBooleanWithoutThrowing() {
        val real = RealCalendarPermissionProbe(context)
        val first = real.hasWriteCalendar()
        val second = real.hasWriteCalendar()
        assertEquals(first, second)
    }

    @Test
    fun theRealNotificationPermissionProbeReturnsAStableBooleanWithoutThrowing() {
        val real = RealNotificationPermissionProbe(context)
        val first = real.isListenerEnabled()
        val second = real.isListenerEnabled()
        assertEquals(first, second)
    }
}

/** A controllable [NotificationPermissionProbe] so the listener gate is exercised in both states. */
private class ToggleNotifProbe : NotificationPermissionProbe {
    var enabled = true

    override fun isListenerEnabled(): Boolean = enabled
}

/** Feeds canned [RawNotification]s through the REAL production filter (the live snapshot is user-gated). */
private class FakeNotifSource : NotificationSource {
    var raws: List<RawNotification> = emptyList()

    override fun activeRaw(): List<RawNotification> = raws
}

/** A controllable [CalendarPermissionProbe] so the WRITE_CALENDAR gate is exercised in both states. */
private class ToggleCalProbe : CalendarPermissionProbe {
    var granted = false

    override fun hasWriteCalendar(): Boolean = granted
}

/** Records the written request instead of inserting into the Calendar Provider. */
private class RecordingWriter : CalendarWriter {
    var last: CalendarEventRequest? = null
    var result: CalendarWriteResult = CalendarWriteResult.Written("evt-1")

    override fun write(request: CalendarEventRequest): CalendarWriteResult {
        last = request
        return result
    }
}
