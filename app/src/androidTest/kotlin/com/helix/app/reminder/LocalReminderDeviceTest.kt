package com.helix.app.reminder

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.core.storage.reminder.LocalReminder
import com.helix.core.storage.reminder.ReminderEvent
import com.helix.core.storage.reminder.ReminderStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class LocalReminderDeviceTest {
    private class Delivery : ReminderDelivery {
        var posts = 0
        var result = "POSTED"
        var exists = false
        var throwOnPost = false
        val scheduled = mutableListOf<LocalReminder>()
        val cancelled = mutableListOf<String>()
        val dismissed = mutableListOf<String>()

        override fun schedule(item: LocalReminder): String {
            scheduled += item
            return "APPROXIMATE"
        }

        override fun cancel(item: LocalReminder) {
            cancelled += item.id
        }

        override fun post(item: LocalReminder): String {
            posts++
            if (throwOnPost) error("Injected publication interruption")
            return result
        }

        override fun posted(event: ReminderEvent) = exists

        override fun dismiss(id: String) {
            dismissed += id
        }
    }

    private class Fixture(
        val store: ReminderStore,
        val delivery: Delivery,
    ) {
        var now = Instant.parse("2026-10-19T09:00:00Z").toEpochMilli()
        val service = ReminderService(store, delivery) { now }

        fun draft(repeat: String = "ONCE") =
            LocalReminder(
                UUID.randomUUID().toString(),
                "Synthetic reminder",
                "Local fixture only",
                Instant
                    .ofEpochMilli(now + 60_000)
                    .atZone(ZoneId.of("UTC"))
                    .toLocalDateTime()
                    .toString(),
                "UTC",
                repeat,
                false,
                null,
            )

        suspend fun saved() = service.items.first().single()
    }

    private fun fixture(block: suspend Fixture.() -> Unit) =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "reminder-test-${UUID.randomUUID()}.db"
            val store = ReminderStore(context, name)
            val fixture = Fixture(store, Delivery())
            try {
                fixture.block()
            } finally {
                fixture.service.close()
                store.close()
                context.deleteDatabase(name)
            }
        }

    @Test fun memoCreateEditCompleteAndCancel() =
        fixture {
            val id = service.save(draft().copy(localTime = ""))
            assertNull(saved().nextAt)
            assertEquals("MEMO", saved().scheduling)
            service.save(saved().copy(body = "Edited synthetic memo"))
            assertEquals(2L, saved().revision)
            service.finish(id, false, requireNotNull(service.get(id)).revision)
            assertEquals("COMPLETED", saved().state)
            service.save(saved()) // Explicitly reopen before cancelling the active entry.
            service.finish(id, true, requireNotNull(service.get(id)).revision)
            assertEquals("CANCELLED", saved().state)
        }

    @Test fun staleAlarmAndDuplicateCallbacksNeverPost() =
        fixture {
            service.save(draft())
            val old = saved()
            service.save(old.copy(title = "Changed"))
            now += 60_000
            service.fire(old.id, old.revision, requireNotNull(old.nextAt))
            assertEquals(0, delivery.posts)
            val current = saved()
            service.fire(current.id, current.revision, requireNotNull(current.nextAt))
            service.fire(current.id, current.revision, requireNotNull(current.nextAt))
            assertEquals(1, delivery.posts)
            assertEquals("POSTED", saved().lastOutcome)
        }

    @Test fun cancellationInvalidatesArmedAlarm() =
        fixture {
            service.save(draft())
            val old = saved()
            service.finish(old.id, true, requireNotNull(service.get(old.id)).revision)
            now += 60_000
            service.fire(old.id, old.revision, requireNotNull(old.nextAt))
            assertEquals(0, delivery.posts)
            assertNull(saved().nextAt)
        }

    @Test fun blockedNotificationIsNeverCalledDelivered() =
        fixture {
            service.save(draft())
            delivery.result = "BLOCKED"
            now += 60_000
            service.reconcile()
            assertEquals("BLOCKED", saved().lastOutcome)
            assertEquals(
                "BLOCKED",
                store.dao
                    .events(saved().id)
                    .single()
                    .outcome,
            )
            service.reconcile()
            assertEquals(1, delivery.posts)
        }

    @Test fun overdueRecoverySkipsOldIntervalsWithoutStorm() =
        fixture {
            service.save(draft("DAILY"))
            now += 30 * 24 * 3600_000L
            service.reconcile()
            assertEquals(0, delivery.posts)
            assertEquals("MISSED", saved().lastOutcome)
            assertTrue(requireNotNull(saved().nextAt) > now)
            assertEquals(1, store.dao.events(saved().id).size)
            assertEquals(saved().nextAt, delivery.scheduled.last().nextAt)
        }

    @Test fun uncertainPublicationIsNotReplayed() =
        fixture {
            service.save(draft())
            val row = saved()
            store.dao.event(ReminderEvent(row.id, row.revision, requireNotNull(row.nextAt), "POSTING", now))
            now += 60_000
            service.reconcile()
            assertEquals("UNKNOWN", saved().lastOutcome)
            assertEquals(0, delivery.posts)
            assertNull(saved().nextAt)
        }

    @Test fun alreadyVisiblePublicationIsRecoveredAsPosted() =
        fixture {
            service.save(draft())
            val row = saved()
            store.dao.event(ReminderEvent(row.id, row.revision, requireNotNull(row.nextAt), "POSTING", now))
            delivery.exists = true
            now += 60_000
            service.reconcile()
            assertEquals("POSTED", saved().lastOutcome)
            assertEquals(0, delivery.posts)
        }

    @Test fun failureAfterClaimLeavesUnknownRatherThanRetryingSideEffect() =
        fixture {
            service.save(draft())
            delivery.throwOnPost = true
            now += 60_000
            assertTrue(runCatching { service.reconcile() }.isFailure)
            assertEquals(
                "POSTING",
                store.dao
                    .events(saved().id)
                    .single()
                    .outcome,
            )
            service.reconcile()
            assertEquals("UNKNOWN", saved().lastOutcome)
            assertEquals(1, delivery.posts)
        }

    @Test fun invalidPastAndStaleEditsDoNotMutate() =
        fixture {
            assertTrue(runCatching { service.save(draft().copy(zone = "Not/AZone")) }.isFailure)
            assertTrue(runCatching { service.save(draft().copy(localTime = "2020-01-01T00:00")) }.isFailure)
            service.save(draft())
            val old = saved()
            service.save(old.copy(body = "New body"))
            assertTrue(runCatching { service.save(old.copy(body = "Stale overwrite")) }.isFailure)
            assertEquals("New body", saved().body)
        }

    @Test fun editingProcessedOneShotDoesNotRearmIt() =
        fixture {
            service.save(draft())
            now += 60_000
            service.reconcile()
            val row = saved()
            service.save(row.copy(body = "Reviewed after notification"))
            assertNull(saved().nextAt)
            assertEquals("NONE", saved().scheduling)
            assertEquals("POSTED", saved().lastOutcome)
            service.reconcile()
            assertEquals(1, delivery.posts)
        }

    @Test fun recoveryFinishesInterruptedCancellationProjection() =
        fixture {
            service.save(draft())
            val row = saved()
            // Cutpoint: database commit succeeded; process died before cancel/dismiss projections.
            store.dao.save(row.copy(state = "CANCELLED", revision = row.revision + 1, nextAt = null))
            delivery.cancelled.clear()
            delivery.dismissed.clear()
            service.reconcile()
            assertEquals(listOf(row.id), delivery.cancelled)
            assertEquals(listOf(row.id), delivery.dismissed)
            assertEquals(0, delivery.posts)
        }

    @Test fun duplicateAfterPublicationFailureAlsoDoesNotReplay() =
        fixture {
            service.save(draft())
            service.save(saved().copy(body = "New revision after clock change"))
            val row = saved()
            now += 60_000
            // A clock rollback can leave more than one UI page of newer-timestamped history.
            repeat(31) { index ->
                store.dao.event(ReminderEvent(row.id, 1, index.toLong(), "POSTED", now + 1_000_000 + index))
            }
            delivery.throwOnPost = true
            assertTrue(
                runCatching {
                    service.fire(row.id, row.revision, requireNotNull(row.nextAt))
                }.isFailure,
            )
            service.fire(row.id, row.revision, requireNotNull(row.nextAt))
            assertEquals(1, delivery.posts)
            assertEquals("UNKNOWN", saved().lastOutcome)
        }
}
