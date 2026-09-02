package com.helix.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.approval.StorageApprovalBroker
import com.helix.core.model.ApprovalDecision
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.policy.ApprovalBinding
import com.helix.core.storage.HelixStorage
import com.helix.tools.framework.ApprovalRequest
import com.helix.tools.framework.NoCancellation
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The production approval wait is a wait/notify rendezvous: the user's decision must wake
 * the waiting dispatch thread IMMEDIATELY, not on the next re-check slice. The old
 * 500ms-poll implementation held a shared worker up to a full slice per card tap; a wait
 * that ever regresses to a slow poll (or stalls) is caught by the latency bound below.
 *
 * The test mirrors the production flow: `acquire` (waiter thread) creates the record and
 * waits; the decision goes through `broker.decide` from ANOTHER thread — exactly what
 * ChatService does with the approval card's tap.
 */
@RunWith(AndroidJUnit4::class)
class ApprovalWakeLatencyDeviceTest {
    @Test
    fun aDecisionWakesTheWaitingAcquireImmediately() {
        val run = System.nanoTime()
        val approvalId = "wake-$run"
        val dbFile = "approval-wake-$run.db"
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val storage =
            HelixStorage.open(
                context,
                dbFile,
                java.io.File(context.filesDir, "approval-wake-$run-content"),
            )
        try {
            val broker =
                StorageApprovalBroker(
                    storage.approvals,
                    object : Clock {
                        override fun now(): Instant = Instant.now()
                    },
                    idGenerator = { approvalId },
                    cardSink = { _, _ -> },
                )
            val binding = seedApprovalChain(storage, run)
            val waiter = startWaiter(storage, broker, binding, approvalId)
            // The decision goes through the broker (record + wake) from this thread —
            // the production UI-tap path. The waiter is blocked in its monitor wait
            // (holding no DB connection), so this runs while the wait is live.
            broker.decide(approvalId, ApprovalDecision.APPROVED)
            waiter.thread.join(5_000)
            val latencyMillis = (System.nanoTime() - waiter.startedAtNanos) / 1_000_000
            assertTrue("the waiter must end after the decision", !waiter.thread.isAlive)
            assertTrue("acquire must return the minted proof", waiter.decided.get())
            val record = storage.approvals.resolve(approvalId)
            assertTrue("the decision row must be APPROVED", record.decision == "APPROVED")
            // The wake is a notify: the full wait must finish well inside a single old
            // poll slice (500ms) plus device noise — a slow-poll regression stalls here.
            assertTrue(
                "decision-to-wake took ${latencyMillis}ms (a 500ms-poll waiter could take the full slice)",
                latencyMillis < 500,
            )
        } finally {
            context.deleteDatabase(dbFile)
            java.io.File(context.filesDir, "approval-wake-$run-content").deleteRecursively()
        }
    }

    /** The approvals row is FK-chained (tool_calls -> turns -> sessions): seed the chain
     * so the waiter's create() cannot fail on the constraint. */
    private fun seedApprovalChain(
        storage: HelixStorage,
        run: Long,
    ): ApprovalBinding {
        val now0 = System.currentTimeMillis()
        storage.sessions.create(
            id = "sess-wake-$run",
            title = "wake test",
            providerId = null,
            modelId = null,
            createdAt = now0,
        )
        storage.turns.start(id = "turn-wake-$run", sessionId = "sess-wake-$run", startedAt = now0)
        storage.toolCalls.append(
            id = "wake-call-$run",
            turnId = "turn-wake-$run",
            callId = "wake-call-$run",
            name = "probe",
            version = "1",
            argsJson = "{}",
            state = "PENDING",
        )
        return ApprovalBinding(
            toolCallId = "wake-call-$run",
            toolName = "probe",
            toolVersion = "1",
            schemaHash = digestOf("w"),
            contractHash = digestOf("c"),
            scopeRef = "unscoped",
            sessionId = "s",
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            uiToken = "u",
            argsHash = digestOf("e"),
        )
    }

    /** The waiter thread: [broker.acquire] blocks until decision/cancel; Approved means
     * the wait ended on the decision and minted the proof. */
    private class WaiterHandle(
        val thread: Thread,
        val decided: AtomicBoolean,
        val startedAtNanos: Long,
    )

    private fun startWaiter(
        storage: HelixStorage,
        broker: StorageApprovalBroker,
        binding: ApprovalBinding,
        approvalId: String,
    ): WaiterHandle {
        val decided = AtomicBoolean(false)
        val started = System.nanoTime()
        val thread =
            Thread {
                val outcome =
                    runCatching {
                        broker.acquire(
                            ApprovalRequest(
                                binding,
                                "device wake test",
                                RiskLevel.L1,
                                NoCancellation,
                            ),
                        )
                    }
                decided.set(outcome.isSuccess)
            }
        thread.name = "wake-waiter"
        thread.start()
        // Wait for the record to exist (the wait slot is registered at that point).
        val deadline = System.currentTimeMillis() + 5_000
        while (
            runCatching { storage.approvals.resolve(approvalId) }.isFailure &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(5)
        }
        return WaiterHandle(thread, decided, started)
    }

    private companion object {
        fun digestOf(seed: String): String =
            java.security.MessageDigest
                .getInstance("SHA-256")
                .digest(seed.toByteArray())
                .joinToString("") { "%02x".format(it) }
    }
}
