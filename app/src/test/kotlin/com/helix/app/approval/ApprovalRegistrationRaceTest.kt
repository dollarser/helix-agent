package com.helix.app.approval

import com.helix.core.model.ApprovalDecision
import com.helix.core.model.Clock
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.policy.ApprovalBinding
import com.helix.core.storage.dao.ApprovalDao
import com.helix.core.storage.entity.ApprovalEntity
import com.helix.core.storage.repository.ApprovalRepository
import com.helix.tools.framework.ApprovalAcquisition
import com.helix.tools.framework.ApprovalRequest
import com.helix.tools.framework.NoCancellation
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ApprovalRegistrationRaceTest {
    @Test
    fun decisionAfterInsertBeforeWaitRegistrationStillWakesAcquire() {
        val dao = PausingInsertDao()
        val broker =
            StorageApprovalBroker(
                ApprovalRepository(dao),
                object : Clock {
                    override fun now(): Instant = Instant.ofEpochMilli(1000)
                },
                { "race-approval" },
                { _, _ -> },
            )
        val binding =
            ApprovalBinding(
                toolCallId = "call",
                toolName = "test",
                toolVersion = "1",
                schemaHash = "a".repeat(64),
                contractHash = "b".repeat(64),
                scopeRef = "scope",
                sessionId = "session",
                executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                uiToken = "token",
                argsHash = "c".repeat(64),
            )
        val acquired = AtomicReference<Result<ApprovalAcquisition>>()
        val decided = AtomicReference<Result<Unit>>()
        val waiter =
            thread(name = "approval-acquire") {
                acquired.set(
                    runCatching {
                        broker.acquire(ApprovalRequest(binding, "race", RiskLevel.L1, NoCancellation))
                    },
                )
            }
        var decider: Thread? = null
        try {
            assertTrue(dao.inserted.await(5, TimeUnit.SECONDS))
            decider =
                thread(name = "approval-decide") {
                    decided.set(runCatching { broker.decide("race-approval", ApprovalDecision.APPROVED) })
                }
            assertTrue(dao.decided.await(5, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (decider.isAlive && decider.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.sleep(1)
            }
            // Old code returns from decide without a slot. Fixed code waits on registration's lock.
            assertTrue(!decider.isAlive || decider.state == Thread.State.BLOCKED)
            dao.releaseInsert.countDown()
            decider.join(5000)
            waiter.join(1000)
            assertTrue("decision was lost between durable insert and wait registration", !waiter.isAlive)
            decided.get().getOrThrow()
            assertTrue(acquired.get().getOrThrow() is ApprovalAcquisition.Approved)
        } finally {
            dao.releaseInsert.countDown()
            broker.cancel("race-approval")
            decider?.join(5000)
            waiter.join(5000)
        }
    }

    private class PausingInsertDao : ApprovalDao {
        val inserted = CountDownLatch(1)
        val releaseInsert = CountDownLatch(1)
        val decided = CountDownLatch(1)
        private val row = AtomicReference<ApprovalEntity>()

        override fun insert(approval: ApprovalEntity) {
            row.set(approval)
            inserted.countDown()
            check(releaseInsert.await(10, TimeUnit.SECONDS))
        }

        override fun byId(id: String): ApprovalEntity? = row.get()?.takeIf { it.id == id }

        override fun byToolCall(toolCallId: String): ApprovalEntity? = row.get()?.takeIf { it.toolCallId == toolCallId }

        override fun decide(
            id: String,
            decision: String,
            decidedAt: Long,
        ): Int {
            val previous = byId(id)
            return if (previous != null && previous.decision == null) {
                row.set(previous.copy(decision = decision, decidedAt = decidedAt))
                decided.countDown()
                1
            } else {
                0
            }
        }

        override fun consumeByBinding(
            id: String,
            bindingHash: String,
            consumedAt: Long,
            now: Long,
        ): Int = error("this test never consumes a proof")

        override fun refundByBinding(
            id: String,
            bindingHash: String,
        ): Int = error("this test never refunds a proof")
    }
}
