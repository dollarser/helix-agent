package com.helix.app.reminder

import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.chat.EgressDisclosure
import com.helix.app.provider.PreparedTextAnalysis
import com.helix.core.model.ProviderResidence
import com.helix.core.storage.reminder.LocalReminder
import com.helix.core.storage.reminder.ReminderEvent
import com.helix.core.storage.reminder.ReminderStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class ReminderInterpretationDeviceTest {
    private val now = Instant.parse("2030-01-01T08:00:00Z")

    @Test fun proposalContainsAnExplicitTimeZoneAndRecurrenceButHasNoSavedIdentity() {
        val proposal = ReminderInterpreter.parse(json("2030-01-02T15:00", "Asia/Shanghai", "WEEKLY"), now)
        val reminder = (proposal as ReminderInterpretation.Proposal).reminder
        assertEquals("", reminder.id)
        assertEquals("Asia/Shanghai", reminder.zone)
        assertEquals("2030-01-02T15:00", reminder.localTime)
        assertEquals("WEEKLY", reminder.repeat)
        assertFalse(reminder.exact)
    }

    @Test fun explicitUndatedMemoAndClarificationAreDistinctFromScheduledProposals() {
        val memo = ReminderInterpreter.parse(json("", "UTC", "ONCE"), now) as ReminderInterpretation.Proposal
        assertEquals("", memo.reminder.localTime)
        val clarification = ReminderInterpreter.parse("{\"clarification\":\"What time?\"}", now)
        assertEquals(ReminderInterpretation.Clarification("What time?"), clarification)
        assertTrue(runCatching { ReminderInterpreter.parse(json("", "UTC", "DAILY"), now) }.isFailure)
    }

    @Test fun pastTimeInvalidZoneUnsupportedRecurrenceAndDstAmbiguityAreRejected() {
        val invalid =
            listOf(
                json("2029-01-01T09:00", "UTC", "ONCE"),
                json("2030-01-02T09:00", "Not/AZone", "ONCE"),
                json("2030-01-02T09:00", "UTC", "WEEKDAYS"),
                json("2030-03-10T02:30", "America/New_York", "ONCE"),
                json("2030-11-03T01:30", "America/New_York", "ONCE"),
            )
        invalid.forEach { assertTrue(runCatching { ReminderInterpreter.parse(it, now) }.isFailure) }
    }

    @Test fun extraActionsWrongTypesOversizedAndTrailingModelOutputFailClosed() {
        val valid = json("2030-01-02T09:00", "UTC", "ONCE")
        val invalid =
            listOf(
                JSONObject(valid).put("execute", "run external command").toString(),
                JSONObject(valid).put("title", 42).toString(),
                JSONObject(valid).put("title", "x".repeat(161)).toString(),
                JSONObject(valid).put("body", "x".repeat(2001)).toString(),
                valid + " trailing instructions",
                "[]",
                "{\"clarification\":\"\"}",
            )
        invalid.forEach { assertTrue(runCatching { ReminderInterpreter.parse(it, now) }.isFailure) }
    }

    @Test fun interpretationOnlyBecomesDurableAfterExplicitSave() =
        fixture { service, store, delivery ->
            val prepared = prepared { json("2030-01-02T09:00", "UTC", "DAILY") }
            assertTrue(store.dao.all().isEmpty())
            val result = prepared.interpret() as ReminderInterpretation.Proposal
            assertTrue(store.dao.all().isEmpty())
            assertEquals(0, delivery.scheduled)
            service.save(result.reminder)
            assertEquals(1, store.dao.all().size)
            assertEquals(1, delivery.scheduled)
            assertEquals(
                "DAILY",
                store.dao
                    .all()
                    .single()
                    .repeat,
            )
        }

    @Test fun cancellationAndFailedInterpretationNeverScheduleAnything() =
        fixture { _, store, delivery ->
            val entered = CompletableDeferred<Unit>()
            val request =
                prepared {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            kotlinx.coroutines.coroutineScope {
                val job = launch { request.interpret() }
                entered.await()
                job.cancelAndJoin()
            }
            assertTrue(runCatching { prepared { error("synthetic model failure") }.interpret() }.isFailure)
            assertTrue(store.dao.all().isEmpty())
            assertEquals(0, delivery.scheduled)
        }

    private fun prepared(execute: suspend () -> String) =
        PreparedReminderInterpretation(
            "Synthetic description",
            now,
            ZoneId.of("UTC"),
            PreparedTextAnalysis(
                EgressDisclosure.EgressTarget("synthetic", "Synthetic", null, "", ProviderResidence.ON_DEVICE_LOCAL),
                "synthetic",
                "Synthetic description",
                "hash",
                execute,
            ),
        )

    private fun json(
        local: String,
        zone: String,
        repeat: String,
    ): String =
        JSONObject()
            .put("title", "Synthetic task")
            .put("body", "Synthetic only")
            .put("localTime", local)
            .put("zone", zone)
            .put("repeat", repeat)
            .toString()

    private class Delivery : ReminderDelivery {
        var scheduled = 0

        override fun schedule(item: LocalReminder): String {
            scheduled++
            return "APPROXIMATE"
        }

        override fun cancel(item: LocalReminder) = Unit

        override fun post(item: LocalReminder) = error("No notification allowed")

        override fun posted(event: ReminderEvent) = false

        override fun dismiss(id: String) = Unit
    }

    private fun fixture(block: suspend (ReminderService, ReminderStore, Delivery) -> Unit) =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "reminder-interpret-${UUID.randomUUID()}.db"
            val store = ReminderStore(context, name)
            val delivery = Delivery()
            val service = ReminderService(store, delivery) { now.toEpochMilli() }
            try {
                block(service, store, delivery)
            } finally {
                service.close()
                store.close()
                context.deleteDatabase(name)
            }
        }
}
