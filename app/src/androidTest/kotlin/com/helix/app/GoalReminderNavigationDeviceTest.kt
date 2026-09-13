package com.helix.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.agent.ModelStreamTerminal
import com.helix.app.agent.TurnStartSpec
import com.helix.app.chat.GoalRunCoordinator
import com.helix.app.chat.GoalTurnStart
import com.helix.app.goal.GoalReminderPayload
import com.helix.app.goal.goalReminderId
import com.helix.core.agent.GoalWakeReason
import com.helix.core.model.GoalBudgets
import com.helix.core.model.SystemClock
import com.helix.core.model.TurnBudgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class GoalReminderNavigationDeviceTest {
    @Test
    fun malformedAndMismatchedRoutesAreIgnored() {
        assertNull(goalReminderId(Intent()))
        for (uri in listOf("https://goal/id", "helix://other/id", "helix://goal/id/extra", "helix://goal/")) {
            assertNull(goalReminderId(Intent().setData(Uri.parse(uri))))
        }
        val intent = Intent().setData(Uri.parse("helix://goal/id"))
        intent.putExtra(GoalReminderPayload.KEY_GOAL_ID, "other")
        assertNull(goalReminderId(intent))
        intent.putExtra(GoalReminderPayload.KEY_GOAL_ID, "id")
        assertEquals("id", goalReminderId(intent))
    }

    @Test
    fun activityReminderOpensBoundSessionWithoutContinuingGoal() {
        val app = ApplicationProvider.getApplicationContext<HelixApplication>()
        // This exercises an existing user's reminder; first-launch acknowledgement
        // is covered separately by FirstLaunchNoticeTest.
        app.appContainer.firstLaunch.markSeen()
        val storage = app.appContainer.storage
        val service = app.appContainer.chatService
        val suffix = UUID.randomUUID().toString()
        val sessionId = "reminder-session-$suffix"
        val clock = SystemClock()
        storage.sessions.create(sessionId, "Reminder fixture", null, null, clock.now().toEpochMilli())
        val coordinator = GoalRunCoordinator(storage, clock) { UUID.randomUUID().toString() }
        val id =
            coordinator.create(
                "Reminder navigation $suffix",
                listOf("Verified result"),
                GoalBudgets(2, 4, 1000, 60000, 10000, 0),
            )
        try {
            parkGoal(coordinator, id, sessionId, suffix)
            val before = storage.goals.resolve(id)
            val runs = storage.goalRuns.listByGoal(id)
            val intent = reminderIntent(app, id)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                awaitReminder(app, id)
                assertEquals(sessionId, service.screen.value.openSessionId)
                assertEquals(before, storage.goals.resolve(id))
                assertEquals(runs, storage.goalRuns.listByGoal(id))
                assertEquals(1, storage.turns.listBySession(sessionId).size)
                service.dismissGoalReminder()
                scenario.recreate()
                scenario.onActivity { activity -> assertEquals(id, goalReminderId(activity.intent)) }
                assertNull(service.reminderGoal.value)
                assertRepeatedNotificationClick(scenario, app, id)
                assertEquals(before, storage.goals.resolve(id))
                assertEquals(runs, storage.goalRuns.listByGoal(id))
                assertNotificationShadeClick(app, id, "Reminder navigation $suffix")
                assertEquals(sessionId, service.screen.value.openSessionId)
                assertEquals(before, storage.goals.resolve(id))
                assertEquals(runs, storage.goalRuns.listByGoal(id))
                assertEquals(1, storage.turns.listBySession(sessionId).size)
            }
        } finally {
            service.closeSession()
            storage.goals.delete(id)
            storage.sessions.archive(sessionId, clock.now().toEpochMilli())
        }
    }

    private fun parkGoal(
        coordinator: GoalRunCoordinator,
        id: String,
        sessionId: String,
        suffix: String,
    ) {
        val started =
            requireNotNull(
                coordinator.start(
                    GoalTurnStart(
                        id,
                        GoalWakeReason.USER_OPEN,
                        TurnStartSpec(sessionId, "turn-$suffix", "model-$suffix", "snapshot", "run"),
                        TurnBudgets(2, 4, 500, 500, 10000),
                    ),
                ),
            )
        started.coordinator.beginModelStream()
        started.coordinator.terminalize(
            ModelStreamTerminal(com.helix.core.model.TurnState.COMPLETED, null),
        )
    }

    private fun assertRepeatedNotificationClick(
        scenario: ActivityScenario<MainActivity>,
        app: HelixApplication,
        id: String,
    ) {
        var original: MainActivity? = null
        scenario.onActivity { original = it }
        com.helix.app.goal
            .goalReminderContentIntent(app, id)
            .send()
        awaitReminder(app, id)
        scenario.onActivity { assertSame("Notification must reuse the existing Activity", original, it) }
    }

    private fun assertNotificationShadeClick(
        app: HelixApplication,
        id: String,
        objective: String,
    ) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            automation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val scheduler =
            com.helix.app.goal.GoalReminderScheduler
                .create(app)
        val label = "Shade-${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        app.appContainer.chatService.dismissGoalReminder()
        try {
            scheduler.scheduleReminder(
                id,
                label,
                com.helix.core.agent
                    .Checkpoint(now),
                now,
            )
            val manager =
                app.getSystemService(
                    android.content.Context.NOTIFICATION_SERVICE,
                ) as android.app.NotificationManager
            val deadline = android.os.SystemClock.elapsedRealtime() + 15000
            while (manager.activeNotifications.none { it.tag == id }) {
                assertTrue("Notification was not posted", android.os.SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(50)
            }
            android.os.ParcelFileDescriptor
                .AutoCloseInputStream(
                    automation.executeShellCommand("cmd statusbar expand-notifications"),
                ).use { it.readBytes() }
            clickNotificationLabel(label)
            awaitReminder(app, id)
            awaitVisibleObjective(objective)
        } finally {
            automation.executeShellCommand("cmd statusbar collapse").close()
            scheduler.cancelReminder(id)
        }
    }

    private fun clickNotificationLabel(label: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = android.os.SystemClock.elapsedRealtime() + 15000
        var clicked = false
        var observedPackage: String? = null
        var matches = 0
        while (!clicked && android.os.SystemClock.elapsedRealtime() < deadline) {
            val root = automation.rootInActiveWindow
            observedPackage = root?.packageName?.toString()
            if (root?.packageName?.toString() == "com.android.systemui") {
                val candidates = notificationNodes(root).filter { it.text?.contains(label) == true }
                matches = candidates.size
                val matching = candidates.firstOrNull()
                var node = matching
                while (node != null && !node.isClickable) node = node.parent
                clicked = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            if (!clicked) Thread.sleep(100)
        }
        assertTrue("Own notification was not clickable: package=$observedPackage matches=$matches", clicked)
    }

    private fun awaitVisibleObjective(objective: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        var visible = false
        while (!visible && android.os.SystemClock.elapsedRealtime() < deadline) {
            visible = automation.rootInActiveWindow?.let { root ->
                notificationNodes(root).any { it.isVisibleToUser && it.text?.contains(objective) == true }
            } == true
            if (!visible) Thread.sleep(100)
        }
        assertTrue("Notification did not display the selected Goal", visible)
    }

    private fun notificationNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val nodes = mutableListOf(root)
        var index = 0
        while (index < nodes.size) {
            val node = nodes[index++]
            repeat(node.childCount) { child -> node.getChild(child)?.let(nodes::add) }
        }
        return nodes
    }

    private fun awaitReminder(
        app: HelixApplication,
        id: String,
    ) {
        val deadline = android.os.SystemClock.elapsedRealtime() + 10000
        while (app.appContainer.chatService.reminderGoal.value != id) {
            assertTrue("New notification click was not consumed", android.os.SystemClock.elapsedRealtime() < deadline)
            Thread.sleep(50)
        }
    }

    private fun reminderIntent(
        app: HelixApplication,
        id: String,
    ): Intent =
        Intent(app, MainActivity::class.java)
            .setData(
                Uri
                    .Builder()
                    .scheme("helix")
                    .authority("goal")
                    .appendPath(id)
                    .build(),
            ).putExtra(GoalReminderPayload.KEY_GOAL_ID, id)
}
