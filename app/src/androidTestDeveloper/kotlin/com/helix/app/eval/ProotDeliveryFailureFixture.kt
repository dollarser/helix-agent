package com.helix.app.eval

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import com.helix.app.HelixApplication
import com.helix.runtime.proot.client.ProotJobClient
import com.helix.runtime.proot.client.ProotRuntimeSupervisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.util.Properties

internal fun announceProotReady(facts: Properties) {
    InstrumentationRegistry.getInstrumentation().sendStatus(
        2,
        Bundle().apply {
            putString(
                "stream",
                "PROOT_GOAL_KILL_READY pid=${android.os.Process.myPid()} job=${facts.getProperty("job")}\n",
            )
        },
    )
}

/** Verify normal saved output or remove only this fixture's transient output to exercise failure recovery. */
internal fun verifyProotDeliveryFailure(
    app: HelixApplication,
    facts: Properties,
    scratchBefore: Set<File>,
    normalResult: Boolean,
    recoverUi: () -> Unit,
) {
    val container = app.appContainer
    val storage = container.storage
    val jobs = ProotJobClient(ProotRuntimeSupervisor(app))
    val job = facts.getProperty("job")
    awaitProotRunning(jobs, job)
    if (!normalResult) unlinkProotOutput(app, scratchBefore)
    val deadline = android.os.SystemClock.elapsedRealtime() + 20000
    val expectedGoal = if (normalResult) "PAUSED" else "INPUT_REQUIRED"
    while (storage.goals.resolve(facts.getProperty("goal")).state != expectedGoal) {
        check(android.os.SystemClock.elapsedRealtime() < deadline) { "Goal did not request input for output review" }
        Thread.sleep(50)
    }
    val goal = storage.goals.resolve(facts.getProperty("goal"))
    val turn = storage.turns.resolve(facts.getProperty("turn"))
    val call = requireNotNull(storage.toolCalls.byTurnAndCallId(turn.id, facts.getProperty("call")))
    assertEquals(if (normalResult) "COMPLETED" else "FAILED", turn.state)
    assertEquals(if (normalResult) "COMPLETED" else "NEEDS_REVIEW", call.state)
    assertEquals(if (normalResult) 2 else 1, goal.modelCalls)
    val run = storage.goalRuns.listByGoal(goal.id).single()
    assertEquals(if (normalResult) "RUN_FINISHED" else "INPUT_REQUIRED(NEEDS_REVIEW)", run.outcome)
    assertTrue(storage.goalUsageReservations.pendingForRun(run.id).isEmpty())
    recoverUi()
    assertEquals(goal, storage.goals.resolve(goal.id))
    assertEquals(turn, storage.turns.resolve(turn.id))
    assertEquals(call, storage.toolCalls.byTurnAndCallId(turn.id, call.callId))
    val record = (jobs.query(job) as ProotJobClient.JobStateOutcome.Ok).record
    assertEquals("SUCCEEDED", record.state.wire)
    assertTrue(record.reconciledAtEpochMs != null)
    assertEquals(1, storage.auditEvents.listByCorrelation(turn.sessionId).count { it.type == "proot.job_prepared" })
    InstrumentationRegistry.getInstrumentation().sendStatus(
        2,
        Bundle().apply { putString("stream", "PROOT_RESULT_VERIFIED normal=$normalResult job=$job\n") },
    )
}

private fun awaitProotRunning(
    jobs: ProotJobClient,
    job: String,
) {
    val deadline = android.os.SystemClock.elapsedRealtime() + 5000
    while (true) {
        val state = (jobs.query(job) as? ProotJobClient.JobStateOutcome.Ok)?.record?.state?.wire
        if (state == "RUNNING") return
        check(state == null || state == "PENDING") { "Missed running boundary: $state" }
        check(android.os.SystemClock.elapsedRealtime() < deadline) { "Job was not submitted" }
        Thread.sleep(50)
    }
}

@androidx.compose.runtime.Composable
@Suppress("FunctionName")
internal fun ProotFixtureRecoveryActions(
    row: com.helix.app.chat.ToolTimelineRow,
    chat: com.helix.app.chat.ChatService,
) {
    com.helix.app.ui.ProotRecoveryActions(
        row,
        chat::inspectInterruptedProot,
        chat::recoverInterruptedProot,
        chat::retryProotAcknowledgement,
    )
}

private fun unlinkProotOutput(
    app: HelixApplication,
    scratchBefore: Set<File>,
) {
    val directory =
        File(app.filesDir, "proot-jobs")
            .listFiles()
            .orEmpty()
            .filter { it !in scratchBefore }
            .single()
    assertTrue(File(directory, "output.zip").delete())
}
