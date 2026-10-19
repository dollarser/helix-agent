package com.helix.app.reminder

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.core.storage.reminder.LocalReminder
import com.helix.core.storage.reminder.ReminderStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** Explicit short-lived synthetic fixtures only. Host owns the actual reboot/force-stop step. */
class ReminderAlarmDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val service get() = (context.applicationContext as HelixApplication).reminders

    private fun fixtureId(): String =
        requireNotNull(
            InstrumentationRegistry.getArguments().getString("helix.reminderFixtureId"),
        ).also { UUID.fromString(it) }

    private fun optedIn() {
        val argument = InstrumentationRegistry.getArguments().getString("helix.reminderLifecycle")
        assumeTrue("Explicit device-lifecycle opt-in absent", argument != null)
        require(argument == "true")
    }

    private suspend fun seed(
        delayMillis: Long,
        id: String = UUID.randomUUID().toString(),
    ): String {
        // Never stop or reboot a device with a durable active Helix Turn.
        val app = context.applicationContext as HelixApplication
        assertTrue(
            app
                .awaitAppContainer()
                .storage.turns
                .listActive()
                .isEmpty(),
        )
        check(service.get(id) == null)
        val due = System.currentTimeMillis() + delayMillis
        val local = Instant.ofEpochMilli(due).atZone(ZoneId.of("UTC")).toLocalDateTime()
        service.save(
            LocalReminder(
                id,
                "Synthetic reminder test",
                "Owned short-lived fixture",
                local.toString(),
                "UTC",
                "ONCE",
                true,
                null,
            ),
        )
        return id
    }

    private suspend fun awaitOutcome(id: String) =
        withTimeout(180_000) {
            var row = requireNotNull(service.get(id))
            while (row.lastOutcome.isBlank()) {
                delay(200)
                row = requireNotNull(service.get(id))
            }
            assertTrue(row.lastOutcome in setOf("POSTED", "BLOCKED"))
            val event = service.events(id).single()
            assertTrue(event.recordedAt >= event.dueAt)
            if (event.outcome == "POSTED") assertTrue(ReminderPlatform(context).posted(event))
            android.util.Log.i(
                "HelixReminderEvidence",
                "occurrence=${event.outcome} lateMs=${event.recordedAt - event.dueAt}",
            )
            row
        }

    private suspend fun cleanup(id: String) {
        service.finish(id, true)
        val store = ReminderStore(context)
        try {
            store.transaction {
                store.dao.deleteEvents(id)
                store.dao.delete(id)
            }
        } finally {
            store.close()
        }
    }

    @Test fun actualSystemAlarmAndNotificationClick(): Unit =
        runBlocking {
            optedIn()
            val id = seed(6000)
            try {
                val row = awaitOutcome(id)
                assertEquals("ACTIVE", row.state)
                if (row.lastOutcome == "POSTED") {
                    context
                        .getSystemService(android.app.NotificationManager::class.java)
                        .activeNotifications
                        .single { it.tag == "local-reminder:$id" }
                        .notification.contentIntent
                        .send()
                } else {
                    ReminderPlatform(context).contentIntent(id).send()
                }
                withTimeout(20_000) {
                    // The Activity accepts a syntactically valid route only; the screen resolves the stored entry.
                    while (!visibleTitle()) delay(100)
                }
                assertEquals(1, service.events(id).size)
                service.reconcile()
                assertEquals(1, service.events(id).size)
            } finally {
                cleanup(id)
            }
        }

    private fun visibleTitle(): Boolean {
        val pending = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(pending::add)
        var visited = 0
        var found = false
        while (pending.isNotEmpty() && visited++ < 500) {
            val node = pending.removeFirst()
            if (node.packageName == context.packageName &&
                node.className == "android.widget.EditText" &&
                node.text?.contains("Synthetic reminder test") == true
            ) {
                found = true
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
        }
        return found
    }

    @Test fun seedRebootFixture(): Unit =
        runBlocking {
            optedIn()
            seed(60_000, fixtureId())
            Unit
        }

    @Test fun seedForceStopFixture(): Unit =
        runBlocking {
            optedIn()
            seed(8000, fixtureId())
            Unit
        }

    @Test fun verifyRecoveredFixture(): Unit =
        runBlocking {
            optedIn()
            val id = fixtureId()
            val fixture = requireNotNull(service.get(id))
            check(fixture.title == "Synthetic reminder test" && fixture.body == "Owned short-lived fixture")
            try {
                val row = awaitOutcome(id)
                android.util.Log.i(
                    "HelixReminderEvidence",
                    "recovery state=${row.lastOutcome}",
                )
            } finally {
                cleanup(id)
            }
            Unit
        }

    /** Read-only inventory for interrupted owned fixtures; cleanup still requires an explicit UUID. */
    @Test fun listOwnedFixtureIds(): Unit =
        runBlocking {
            optedIn()
            service.items
                .first()
                .filter {
                    it.title == "Synthetic reminder test" && it.body == "Owned short-lived fixture"
                }.forEach { android.util.Log.i("HelixReminderEvidence", "ownedFixtureId=${it.id}") }
        }
}
