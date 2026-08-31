package com.helix.app.goal

import com.helix.core.agent.Checkpoint
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * JVM tests for the reminder scheduling decision (work name, delay, payload). The WorkManager
 * REPLACE effect (one live reminder per goal) is device-verified by GoalReminderTest; these
 * tests pin the composition that decides what gets enqueued where.
 */
class GoalReminderSchedulerTest {
    private class RecordingEnqueuer : ReminderEnqueuer {
        data class Enqueue(
            val workName: String,
            val delayMillis: Long,
            val goalId: String,
            val objective: String,
        )

        val enqueues = ConcurrentLinkedQueue<Enqueue>()
        val cancellations = ConcurrentLinkedQueue<String>()

        override fun enqueueOrReplace(
            workName: String,
            delayMillis: Long,
            goalId: String,
            objective: String,
        ) {
            enqueues.add(Enqueue(workName, delayMillis, goalId, objective))
        }

        override fun cancel(workName: String) {
            cancellations.add(workName)
        }
    }

    @Test
    fun scheduleUsesOneStableWorkNamePerGoalAndCarriesTheCheckpointPayload() {
        val enqueuer = RecordingEnqueuer()
        val scheduler = GoalReminderScheduler(enqueuer)
        scheduler.scheduleReminder("goal-1", "Investigate the login flow", Checkpoint(10_000L), 0L)
        scheduler.scheduleReminder("goal-2", "Other goal", Checkpoint(10_000L), 0L)
        assertEquals(2, enqueuer.enqueues.size)
        val first = enqueuer.enqueues.first()
        assertEquals("goal-reminder-goal-1", first.workName)
        assertEquals(10_000L, first.delayMillis)
        assertEquals("goal-1", first.goalId)
        assertEquals("Investigate the login flow", first.objective)
        // A reschedule targets the same unique work name, so the production enqueuer's
        // unique-work enqueue replaces the pending reminder instead of stacking one.
        scheduler.scheduleReminder("goal-1", "Investigate the login flow", Checkpoint(20_000L), 5_000L)
        assertEquals(3, enqueuer.enqueues.size)
        assertEquals("goal-reminder-goal-1", enqueuer.enqueues.last().workName)
        assertEquals(15_000L, enqueuer.enqueues.last().delayMillis)
    }

    @Test
    fun expiredCheckpointSchedulesImmediately() {
        val enqueuer = RecordingEnqueuer()
        val scheduler = GoalReminderScheduler(enqueuer)
        // Checkpoint 4 seconds in the past: the delay clamps to zero (deferral recovery —
        // the reminder is due now, WorkManager may still defer it under Doze).
        scheduler.scheduleReminder("goal-1", "Investigate", Checkpoint(1_000L), 5_000L)
        assertEquals(1, enqueuer.enqueues.size)
        assertEquals(0L, enqueuer.enqueues.first().delayMillis)
    }

    @Test
    fun nullCheckpointSchedulesNothing() {
        val enqueuer = RecordingEnqueuer()
        val scheduler = GoalReminderScheduler(enqueuer)
        scheduler.scheduleReminder("goal-1", "Investigate", null, 0L)
        assertEquals(0, enqueuer.enqueues.size)
    }

    @Test
    fun cancelTargetsTheSameUniqueWorkName() {
        val enqueuer = RecordingEnqueuer()
        val scheduler = GoalReminderScheduler(enqueuer)
        scheduler.cancelReminder("goal-1")
        assertEquals(1, enqueuer.cancellations.size)
        assertEquals("goal-reminder-goal-1", enqueuer.cancellations.first())
    }
}
