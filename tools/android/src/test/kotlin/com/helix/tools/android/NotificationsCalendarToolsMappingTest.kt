package com.helix.tools.android

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolOperationClass
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * HXA-065 (verification matrix row `:tools:android:testDebugUnitTest`): the `notifications.*` /
 * `calendar.*` tools' fail-closed outcome → result mapping + the pure-JVM notification filter. A
 * [FakeNotificationsBridge] / [FakeCalendarBridge] returns canned port outcomes; each tool's executor
 * must never claim success on a refusal or a timeout, must fail invalid/missing arguments, and must
 * emit no JSON nulls. The mapping IS the tool layer — the listener / provider / permission work lives
 * in the port impl (this module), exercised on device. [filterNotifications] is pure JVM, so its
 * allowlist + window + bounding logic is pinned here in isolation.
 */
class NotificationsCalendarToolsMappingTest {
    private val notif = FakeNotificationsBridge()
    private val cal = FakeCalendarBridge()

    private val noCancel =
        object : CancelSignal {
            override fun isCancelled(): Boolean = false
        }

    private fun call(
        name: String,
        args: JsonObject,
    ): ExecutableToolCall =
        ExecutableToolCall(
            toolCallId = "call-1",
            toolName = name,
            toolVersion = "1",
            args = args,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            deadline = Instant.now().plusSeconds(30),
            cancel = noCancel,
        )

    private fun run(
        name: String,
        executor: ToolExecutor,
        args: JsonObject,
    ): ToolExecutorResult = executor.execute(call(name, args))

    private fun json(result: ToolExecutorResult): JsonObject {
        val c = result as? ToolExecutorResult.Completed ?: error("expected Completed, got $result")
        return c.output.jsonObject
    }

    private fun failed(result: ToolExecutorResult): ToolExecutorResult.Failed {
        val f = result as? ToolExecutorResult.Failed ?: error("expected Failed, got $result")
        return f
    }

    /** Walks a result's Completed output and asserts it carries no JSON nulls anywhere. */
    private fun assertNoNulls(result: ToolExecutorResult) {
        val c = result as? ToolExecutorResult.Completed ?: error("expected Completed, got $result")

        fun walk(e: JsonElement) {
            assertTrue("found a JSON null in tool output", e !is JsonNull)
            when (e) {
                is JsonObject -> e.values.forEach { walk(it) }
                is JsonArray -> e.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(c.output)
    }

    // ── notifications.query ───────────────────────────────────────────────────────────────

    @Test
    fun queryEmitsQueriedWithBoundedEntries() {
        notif.outcome =
            NotificationQueryOutcome(
                status = NotificationQueryStatus.QUERIED,
                entries =
                    listOf(
                        NotificationEntry("com.app.a", "Hello", "body", 2000L),
                        NotificationEntry("com.app.b", "Second", "", 1000L),
                    ),
                excludedCount = 3,
                reason = "",
            )
        val result =
            run(
                NotificationsQueryTool.NAME,
                NotificationsQueryTool.executor(notif),
                buildJsonObject {
                    put("allowedPackages", JsonArray(listOf(JsonPrimitive("com.app.a"), JsonPrimitive("com.app.b"))))
                    put("sinceEpochMillis", JsonPrimitive(0L))
                    put("untilEpochMillis", JsonPrimitive(9999L))
                },
            )
        val out = json(result)
        assertEquals("queried", out.getValue("status").jsonPrimitive.content)
        assertEquals(2L, out.getValue("count").jsonPrimitive.longOrNull)
        assertEquals(3L, out.getValue("excludedCount").jsonPrimitive.longOrNull)
        assertEquals(2, out.getValue("entries").jsonArray.size)
        val entry0 = out.getValue("entries").jsonArray[0].jsonObject
        assertEquals("com.app.a", entry0.getValue("packageName").jsonPrimitive.content)
        assertEquals(2000L, entry0.getValue("postedEpochMillis").jsonPrimitive.longOrNull)
        assertEquals("", out.getValue("reason").jsonPrimitive.content)
        assertEquals(listOf("com.app.a", "com.app.b"), notif.lastRequest?.allowedPackages)
        assertNoNulls(result)
    }

    @Test
    fun queryPassesTheWindowToThePort() {
        notif.outcome = NotificationQueryOutcome(NotificationQueryStatus.QUERIED, emptyList(), 0, "")
        run(
            NotificationsQueryTool.NAME,
            NotificationsQueryTool.executor(notif),
            buildJsonObject {
                put("allowedPackages", JsonArray(listOf(JsonPrimitive("com.app.a"))))
                put("sinceEpochMillis", JsonPrimitive(100L))
                put("untilEpochMillis", JsonPrimitive(900L))
            },
        )
        val r = notif.lastRequest
        assertEquals(100L, r?.sinceEpochMillis)
        assertEquals(900L, r?.untilEpochMillis)
    }

    @Test
    fun queryPermissionMissingIsAStableStatusNotAFakeEmptySuccess() {
        notif.outcome =
            NotificationQueryOutcome(
                NotificationQueryStatus.PERMISSION_MISSING,
                emptyList(),
                0,
                "permission-missing",
            )
        val out =
            json(
                run(
                    NotificationsQueryTool.NAME,
                    NotificationsQueryTool.executor(notif),
                    buildJsonObject {
                        put("allowedPackages", JsonArray(listOf(JsonPrimitive("com.app.a"))))
                        put("sinceEpochMillis", JsonPrimitive(0L))
                        put("untilEpochMillis", JsonPrimitive(9L))
                    },
                ),
            )
        assertEquals("permission-missing", out.getValue("status").jsonPrimitive.content)
        assertEquals(0L, out.getValue("count").jsonPrimitive.longOrNull)
        assertEquals(0, out.getValue("entries").jsonArray.size)
        assertEquals("permission-missing", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun queryErrorIsFailed() {
        notif.outcome = NotificationQueryOutcome(NotificationQueryStatus.ERROR, emptyList(), 0, "boom")
        val f =
            failed(
                run(
                    NotificationsQueryTool.NAME,
                    NotificationsQueryTool.executor(notif),
                    buildJsonObject {
                        put("allowedPackages", JsonArray(listOf(JsonPrimitive("com.app.a"))))
                        put("sinceEpochMillis", JsonPrimitive(0L))
                        put("untilEpochMillis", JsonPrimitive(9L))
                    },
                ),
            )
        assertTrue(f.detail.contains("boom"))
    }

    @Test
    fun queryMissingOrEmptyAllowlistIsFailed() {
        val missing =
            run(
                NotificationsQueryTool.NAME,
                NotificationsQueryTool.executor(notif),
                buildJsonObject {
                    put("sinceEpochMillis", JsonPrimitive(0L))
                    put("untilEpochMillis", JsonPrimitive(9L))
                },
            )
        assertTrue(missing is ToolExecutorResult.Failed)
        val empty =
            run(
                NotificationsQueryTool.NAME,
                NotificationsQueryTool.executor(notif),
                buildJsonObject {
                    put("allowedPackages", JsonArray(emptyList()))
                    put("sinceEpochMillis", JsonPrimitive(0L))
                    put("untilEpochMillis", JsonPrimitive(9L))
                },
            )
        assertTrue(empty is ToolExecutorResult.Failed)
    }

    @Test
    fun queryNonStringPackageEntryIsFailed() {
        val f =
            run(
                NotificationsQueryTool.NAME,
                NotificationsQueryTool.executor(notif),
                buildJsonObject {
                    put("allowedPackages", JsonArray(listOf(JsonPrimitive(123))))
                    put("sinceEpochMillis", JsonPrimitive(0L))
                    put("untilEpochMillis", JsonPrimitive(9L))
                },
            )
        assertTrue(f is ToolExecutorResult.Failed)
    }

    @Test
    fun queryInvalidWindowIsFailed() {
        val untilNotAfterSince =
            run(
                NotificationsQueryTool.NAME,
                NotificationsQueryTool.executor(notif),
                buildJsonObject {
                    put("allowedPackages", JsonArray(listOf(JsonPrimitive("a"))))
                    put("sinceEpochMillis", JsonPrimitive(100L))
                    put("untilEpochMillis", JsonPrimitive(100L))
                },
            )
        assertTrue(untilNotAfterSince is ToolExecutorResult.Failed)
        val missingSince =
            run(
                NotificationsQueryTool.NAME,
                NotificationsQueryTool.executor(notif),
                buildJsonObject {
                    put("allowedPackages", JsonArray(listOf(JsonPrimitive("a"))))
                    put("untilEpochMillis", JsonPrimitive(100L))
                },
            )
        assertTrue(missingSince is ToolExecutorResult.Failed)
        val tooWide =
            run(
                NotificationsQueryTool.NAME,
                NotificationsQueryTool.executor(notif),
                buildJsonObject {
                    put("allowedPackages", JsonArray(listOf(JsonPrimitive("a"))))
                    put("sinceEpochMillis", JsonPrimitive(0L))
                    put("untilEpochMillis", JsonPrimitive(25L * 86_400_000))
                },
            )
        assertTrue(tooWide is ToolExecutorResult.Failed)
    }

    // ── calendar.prepare_event ────────────────────────────────────────────────────────────

    @Test
    fun preparePreparedEmitsTheDraft() {
        cal.prepareOutcome =
            CalendarPrepareOutcome(
                status = CalendarPrepareStatus.PREPARED,
                draftId = "draft-42",
                title = "Standup",
                startEpochMillis = 1000L,
                endEpochMillis = 2000L,
                location = "Room 1",
                notes = "weekly",
                timeZoneId = "Asia/Shanghai",
                reason = "",
            )
        val result =
            run(
                CalendarPrepareEventTool.NAME,
                CalendarPrepareEventTool.executor(cal),
                buildJsonObject {
                    put("title", JsonPrimitive("Standup"))
                    put("startEpochMillis", JsonPrimitive(1000L))
                    put("endEpochMillis", JsonPrimitive(2000L))
                    put("location", JsonPrimitive("Room 1"))
                    put("notes", JsonPrimitive("weekly"))
                    put("timeZoneId", JsonPrimitive("Asia/Shanghai"))
                },
            )
        val out = json(result)
        assertEquals("prepared", out.getValue("status").jsonPrimitive.content)
        assertEquals("draft-42", out.getValue("draftId").jsonPrimitive.content)
        assertEquals("Standup", out.getValue("title").jsonPrimitive.content)
        assertEquals(1000L, out.getValue("startEpochMillis").jsonPrimitive.longOrNull)
        assertEquals(2000L, out.getValue("endEpochMillis").jsonPrimitive.longOrNull)
        assertEquals("Room 1", out.getValue("location").jsonPrimitive.content)
        assertEquals("Asia/Shanghai", out.getValue("timeZoneId").jsonPrimitive.content)
        assertEquals("", out.getValue("reason").jsonPrimitive.content)
        assertNoNulls(result)
    }

    @Test
    fun prepareInvalidIsAStableStatusWithReason() {
        cal.prepareOutcome =
            CalendarPrepareOutcome(
                status = CalendarPrepareStatus.INVALID,
                draftId = "",
                title = "",
                startEpochMillis = 0,
                endEpochMillis = 0,
                location = "",
                notes = "",
                timeZoneId = "",
                reason = "end must be after start",
            )
        val out =
            json(
                run(
                    CalendarPrepareEventTool.NAME,
                    CalendarPrepareEventTool.executor(cal),
                    buildJsonObject {
                        put("title", JsonPrimitive("X"))
                        put("startEpochMillis", JsonPrimitive(2000L))
                        put("endEpochMillis", JsonPrimitive(1000L))
                    },
                ),
            )
        assertEquals("invalid", out.getValue("status").jsonPrimitive.content)
        assertEquals("", out.getValue("draftId").jsonPrimitive.content)
        assertEquals("end must be after start", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun prepareErrorIsFailed() {
        cal.prepareOutcome =
            CalendarPrepareOutcome(
                status = CalendarPrepareStatus.ERROR,
                draftId = "",
                title = "",
                startEpochMillis = 0,
                endEpochMillis = 0,
                location = "",
                notes = "",
                timeZoneId = "",
                reason = "boom",
            )
        val f =
            failed(
                run(
                    CalendarPrepareEventTool.NAME,
                    CalendarPrepareEventTool.executor(cal),
                    buildJsonObject {
                        put("title", JsonPrimitive("X"))
                        put("startEpochMillis", JsonPrimitive(0L))
                        put("endEpochMillis", JsonPrimitive(1L))
                    },
                ),
            )
        assertTrue(f.detail.contains("boom"))
    }

    @Test
    fun prepareMissingRequiredFieldIsFailed() {
        val noTitle =
            run(
                CalendarPrepareEventTool.NAME,
                CalendarPrepareEventTool.executor(cal),
                buildJsonObject {
                    put("startEpochMillis", JsonPrimitive(0L))
                    put("endEpochMillis", JsonPrimitive(1L))
                },
            )
        assertTrue(noTitle is ToolExecutorResult.Failed)
        val noStart =
            run(
                CalendarPrepareEventTool.NAME,
                CalendarPrepareEventTool.executor(cal),
                buildJsonObject {
                    put("title", JsonPrimitive("X"))
                    put("endEpochMillis", JsonPrimitive(1L))
                },
            )
        assertTrue(noStart is ToolExecutorResult.Failed)
    }

    // ── calendar.commit_event ─────────────────────────────────────────────────────────────

    @Test
    fun commitCommittedEmitsTheEventId() {
        cal.commitOutcome = CalendarCommitOutcome(CalendarCommitStatus.COMMITTED, "draft-42", "evt-7", "")
        val out =
            json(
                run(
                    CalendarCommitEventTool.NAME,
                    CalendarCommitEventTool.executor(cal),
                    buildJsonObject { put("draftId", JsonPrimitive("draft-42")) },
                ),
            )
        assertEquals("committed", out.getValue("status").jsonPrimitive.content)
        assertEquals("draft-42", out.getValue("draftId").jsonPrimitive.content)
        assertEquals("evt-7", out.getValue("eventId").jsonPrimitive.content)
        assertEquals("", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun commitPermissionMissingIsAStableStatus() {
        cal.commitOutcome =
            CalendarCommitOutcome(
                CalendarCommitStatus.PERMISSION_MISSING,
                "draft-42",
                "",
                "permission-missing",
            )
        val out =
            json(
                run(
                    CalendarCommitEventTool.NAME,
                    CalendarCommitEventTool.executor(cal),
                    buildJsonObject { put("draftId", JsonPrimitive("draft-42")) },
                ),
            )
        assertEquals("permission-missing", out.getValue("status").jsonPrimitive.content)
        assertEquals("", out.getValue("eventId").jsonPrimitive.content)
        assertEquals("permission-missing", out.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun commitDraftNotFoundAndNoHandlerAreStableStatuses() {
        cal.commitOutcome = CalendarCommitOutcome(CalendarCommitStatus.DRAFT_NOT_FOUND, "gone", "", "draft-not-found")
        val out =
            json(
                run(
                    CalendarCommitEventTool.NAME,
                    CalendarCommitEventTool.executor(cal),
                    buildJsonObject { put("draftId", JsonPrimitive("gone")) },
                ),
            )
        assertEquals("draft-not-found", out.getValue("status").jsonPrimitive.content)
        cal.commitOutcome = CalendarCommitOutcome(CalendarCommitStatus.NO_HANDLER, "d", "", "no-handler")
        val out2 =
            json(
                run(
                    CalendarCommitEventTool.NAME,
                    CalendarCommitEventTool.executor(cal),
                    buildJsonObject { put("draftId", JsonPrimitive("d")) },
                ),
            )
        assertEquals("no-handler", out2.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun commitErrorIsFailedAndMissingDraftIdIsFailed() {
        cal.commitOutcome = CalendarCommitOutcome(CalendarCommitStatus.ERROR, "d", "", "calendar write failed")
        val f =
            failed(
                run(
                    CalendarCommitEventTool.NAME,
                    CalendarCommitEventTool.executor(cal),
                    buildJsonObject { put("draftId", JsonPrimitive("d")) },
                ),
            )
        assertTrue(f.detail.contains("calendar write failed"))
        val missing =
            run(
                CalendarCommitEventTool.NAME,
                CalendarCommitEventTool.executor(cal),
                buildJsonObject {},
            )
        assertTrue(missing is ToolExecutorResult.Failed)
    }

    // ── filterNotifications (pure JVM) ────────────────────────────────────────────────────

    @Test
    fun filterKeepsOnlyAllowlistedPackagesInWindowNewestFirst() {
        val raw =
            listOf(
                RawNotification("com.app.a", "a-new", "", 300L),
                RawNotification("com.app.a", "a-old", "", 100L),
                RawNotification("com.app.b", "b", "", 200L),
                RawNotification("com.app.c", "c-excluded", "", 250L),
                RawNotification("com.app.a", "a-out-of-window", "", 999L),
            )
        val (entries, excluded) = filterNotifications(raw, setOf("com.app.a", "com.app.b"), 0L, 500L)
        assertEquals(listOf("com.app.a", "com.app.b", "com.app.a"), entries.map { it.packageName })
        assertEquals(listOf(300L, 200L, 100L), entries.map { it.postedEpochMillis })
        assertEquals(1, excluded)
    }

    @Test
    fun filterBoundsTitleAndTextAndCapsEntries() {
        val raw =
            (0 until 40)
                .map { i ->
                    RawNotification(
                        "com.app.a",
                        "T".repeat(MAX_NOTIFICATION_TITLE + 50),
                        "X".repeat(MAX_NOTIFICATION_TEXT + 50),
                        i.toLong(),
                    )
                }
        val (entries, excluded) = filterNotifications(raw, setOf("com.app.a"), 0L, 100L)
        assertEquals(MAX_NOTIFICATION_ENTRIES, entries.size)
        assertEquals(excluded, 0)
        assertTrue(entries.all { it.title.length == MAX_NOTIFICATION_TITLE })
        assertTrue(entries.all { it.text.length == MAX_NOTIFICATION_TEXT })
        assertEquals(39L, entries.first().postedEpochMillis)
    }

    @Test
    fun filterEmptyWindowYieldsNothing() {
        val (entries, excluded) =
            filterNotifications(
                listOf(RawNotification("a", "t", "", 5L)),
                setOf("a"),
                untilEpochMillis = 4L,
                sinceEpochMillis = 10L,
            )
        assertTrue(entries.isEmpty())
        assertEquals(0, excluded)
    }

    // ── descriptor contract fields ────────────────────────────────────────────────────────

    private fun checkBase(
        d: ToolDescriptor,
        name: String,
    ) {
        assertEquals(name, d.name.value)
        assertEquals(1, d.version.value)
        assertEquals(ExecutionTargetType.LOCAL_ANDROID, d.executionTarget)
        assertTrue(d.origin is ToolOrigin.BuiltInOrigin)
        assertTrue(d.requiredCapabilities.isEmpty())
    }

    @Test
    fun descriptorsCarryTheExpectedContract() {
        val q = NotificationsQueryTool.descriptor()
        checkBase(q, "notifications.query")
        assertEquals(RiskLevel.L1, q.baseRisk)
        assertEquals(ToolOperationClass.READ_ONLY, q.operationClass)

        val p = CalendarPrepareEventTool.descriptor()
        checkBase(p, "calendar.prepare_event")
        assertEquals(RiskLevel.L1, p.baseRisk)
        assertEquals(ToolOperationClass.READ_ONLY, p.operationClass)

        val c = CalendarCommitEventTool.descriptor()
        checkBase(c, "calendar.commit_event")
        assertEquals(RiskLevel.L2, c.baseRisk)
        assertEquals(ToolOperationClass.EXTERNAL_ACTION, c.operationClass)
    }

    @Test
    fun queryIsIdempotentAndTheCalendarToolsAreNot() {
        assertEquals(Idempotency.IDEMPOTENT, NotificationsQueryTool.descriptor().idempotency)
        assertEquals(Idempotency.NON_IDEMPOTENT, CalendarPrepareEventTool.descriptor().idempotency)
        assertEquals(Idempotency.NON_IDEMPOTENT, CalendarCommitEventTool.descriptor().idempotency)
    }
}

/** A settable fake of the [NotificationsBridge] port for the mapping tests. */
private class FakeNotificationsBridge : NotificationsBridge {
    var outcome: NotificationQueryOutcome =
        NotificationQueryOutcome(NotificationQueryStatus.QUERIED, emptyList(), 0, "")

    var lastRequest: NotificationQueryRequest? = null

    override fun query(request: NotificationQueryRequest): NotificationQueryOutcome {
        lastRequest = request
        return outcome
    }
}

/** A settable fake of the [CalendarBridge] port for the mapping tests. */
private class FakeCalendarBridge : CalendarBridge {
    var prepareOutcome: CalendarPrepareOutcome =
        CalendarPrepareOutcome(CalendarPrepareStatus.PREPARED, "d", "t", 0, 0, "", "", "", "")

    var commitOutcome: CalendarCommitOutcome =
        CalendarCommitOutcome(CalendarCommitStatus.COMMITTED, "d", "e", "")

    override fun prepareEvent(request: CalendarEventRequest): CalendarPrepareOutcome = prepareOutcome

    override fun commitEvent(draftId: String): CalendarCommitOutcome = commitOutcome
}
