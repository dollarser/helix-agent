package com.helix.core.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.core.model.ApprovalDecision
import com.helix.core.model.ExecutionTargetType
import com.helix.core.policy.ApprovalBinding
import com.helix.core.policy.ApprovalMintOutcome
import com.helix.core.policy.ApprovalProof
import com.helix.core.policy.MintRejectionCode
import com.helix.core.storage.content.FileContentStore
import com.helix.core.storage.entity.ToolCallEntity
import com.helix.core.storage.repository.ApprovalRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * HXA-034 device acceptance (verification-matrix row): the full approval lifecycle on real
 * Room — pending/DENIED/expired records can neither mint nor consume an Approval Proof, a
 * proof is bound to the exact binding hash (replay to another session/workspace/args fails),
 * the window caps are enforced at creation, and concurrent consumption succeeds exactly once
 * (the atomic SQL guard, not caller pre-checks).
 */
@RunWith(AndroidJUnit4::class)
class ApprovalProofLifecycleTest {
    private lateinit var context: Context
    private lateinit var storage: HelixStorage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
        val db = Room.databaseBuilder(context, HelixDatabase::class.java, DB_NAME).build()
        storage = HelixStorage(db, FileContentStore(File(context.cacheDir, "content-$DB_NAME")), TestSecretStore())
    }

    @Test
    fun pendingCannotMintOrConsume() {
        val call = seedToolCall("call-pending")
        val approval =
            createApproval("approval-pending", call, "workspace:ws-1", "session-pending", "a".repeat(64), 100L, 20_000L)
        assertEquals(ApprovalMintOutcome.Rejected(MintRejectionCode.PENDING), storage.approvals.mint(approval.id, 150L))
        // A forged proof (right id, right stored hash) still cannot consume: the SQL guard
        // requires decision = 'APPROVED', which a pending record does not have.
        assertThrows(IllegalArgumentException::class.java) {
            storage.approvals.consume(ApprovalProof(approval.id, approval.bindingHash), 150L, 150L)
        }
    }

    @Test
    fun deniedIsProcessedButNeverACredential() {
        val call = seedToolCall("call-denied")
        val approval =
            createApproval("approval-denied", call, "workspace:ws-1", "session-denied", "b".repeat(64), 100L, 20_000L)
        storage.approvals.decide(approval.id, ApprovalDecision.DENIED, 150L)
        // decision != null and decidedAt != null: a processed decision — still not a credential.
        assertEquals(ApprovalMintOutcome.Rejected(MintRejectionCode.DENIED), storage.approvals.mint(approval.id, 160L))
        assertThrows(IllegalArgumentException::class.java) {
            storage.approvals.consume(ApprovalProof(approval.id, approval.bindingHash), 160L, 160L)
        }
        assertEquals(null, storage.approvals.resolve(approval.id).consumedAt)
    }

    @Test
    fun expiredApprovedCannotMintOrConsume() {
        val call = seedToolCall("call-expired")
        val approval =
            createApproval("approval-expired", call, "workspace:ws-1", "session-expired", "c".repeat(64), 100L, 1_000L)
        storage.approvals.decide(approval.id, ApprovalDecision.APPROVED, 150L)
        // Inside the window the proof mints and consumes.
        val proof = (storage.approvals.mint(approval.id, 900L) as ApprovalMintOutcome.Minted).proof
        storage.approvals.consume(proof, 900L, 900L)

        val secondCall = seedToolCall("call-expired-2")
        val expired =
            createApproval(
                "approval-expired-2",
                secondCall,
                "workspace:ws-1",
                "session-expired",
                "d".repeat(64),
                100L,
                1_000L,
            )
        storage.approvals.decide(expired.id, ApprovalDecision.APPROVED, 150L)
        // now == expiresAt is already expired (the window is createdAt < now < expiresAt).
        assertEquals(
            ApprovalMintOutcome.Rejected(MintRejectionCode.EXPIRED),
            storage.approvals.mint(expired.id, 1_000L),
        )
        assertEquals(
            ApprovalMintOutcome.Rejected(MintRejectionCode.EXPIRED),
            storage.approvals.mint(expired.id, 5_000L),
        )
        assertThrows(IllegalArgumentException::class.java) {
            storage.approvals.consume(ApprovalProof(expired.id, expired.bindingHash), 1_000L, 1_000L)
        }
    }

    @Test
    fun creationRejectsMissingOrOverlongWindows() {
        val call = seedToolCall("call-window")
        assertThrows(IllegalArgumentException::class.java) {
            createApproval("approval-w-1", call, "workspace:ws-1", "session-w", "e".repeat(64), 100L, 100L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createApproval("approval-w-2", call, "workspace:ws-1", "session-w", "e".repeat(64), 100L, 50L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            createApproval(
                "approval-w-3",
                call,
                "workspace:ws-1",
                "session-w",
                "e".repeat(64),
                0L,
                ApprovalRepository.MAX_APPROVAL_TTL_MILLIS + 1L,
            )
        }
    }

    @Test
    fun proofIsBoundToTheExactBindingHash() {
        val callA = seedToolCall("call-bind-a")
        val callB = seedToolCall("call-bind-b")
        val approvalA =
            createApproval("approval-bind-a", callA, "workspace:ws-1", "session-a", "f".repeat(64), 100L, 20_000L)
        storage.approvals.decide(approvalA.id, ApprovalDecision.APPROVED, 150L)
        val proofA = (storage.approvals.mint(approvalA.id, 160L) as ApprovalMintOutcome.Minted).proof

        // Replay 1: the same approval id with a different binding hash (another session's
        // binding) — the stored hash check fails inside the SQL guard.
        val foreignBinding =
            binding("call-bind-b", scopeRef = "workspace:ws-1", sessionId = "session-b", argsHash = "5".repeat(64))
        assertThrows(IllegalArgumentException::class.java) {
            storage.approvals.consume(ApprovalProof(approvalA.id, foreignBinding.hash), 160L, 160L)
        }
        // The record is untouched: the genuine proof still consumes.
        storage.approvals.consume(proofA, 160L, 160L)
        assertEquals(160L, storage.approvals.resolve(approvalA.id).consumedAt)

        // Replay 3: record A is consumed now — the same proof cannot consume again, and
        // minting is rejected as CONSUMED (decision and consumedAt are processing facts,
        // not credentials).
        assertThrows(IllegalArgumentException::class.java) {
            storage.approvals.consume(proofA, 170L, 170L)
        }
        assertEquals(
            ApprovalMintOutcome.Rejected(MintRejectionCode.CONSUMED),
            storage.approvals.mint(approvalA.id, 190L),
        )

        // A different tool call is a different record with its own binding: proofB (record B)
        // works only on B.
        val approvalB =
            createApproval("approval-bind-b", callB, "workspace:ws-1", "session-b", "0".repeat(64), 100L, 20_000L)
        storage.approvals.decide(approvalB.id, ApprovalDecision.APPROVED, 170L)
        val proofB = (storage.approvals.mint(approvalB.id, 180L) as ApprovalMintOutcome.Minted).proof
        storage.approvals.consume(proofB, 181L, 181L)
        assertEquals(181L, storage.approvals.resolve(approvalB.id).consumedAt)
        assertThrows(IllegalArgumentException::class.java) {
            storage.approvals.consume(proofB, 182L, 182L)
        }
    }

    @Test
    fun concurrentConsumeSucceedsExactlyOnce() {
        val call = seedToolCall("call-concurrent")
        val approval =
            createApproval("approval-concurrent", call, "workspace:ws-1", "session-c", "1".repeat(64), 100L, 20_000L)
        storage.approvals.decide(approval.id, ApprovalDecision.APPROVED, 150L)
        val proof = (storage.approvals.mint(approval.id, 160L) as ApprovalMintOutcome.Minted).proof

        val workers = 8
        val pool = Executors.newFixedThreadPool(workers)
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val successes = Collections.synchronizedList(mutableListOf<Long>())
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        try {
            for (i in 0 until workers) {
                val consumedAt = 160L + i
                pool.execute {
                    ready.countDown()
                    start.await()
                    try {
                        storage.approvals.consume(proof, consumedAt, consumedAt)
                        successes.add(consumedAt)
                    } catch (e: Throwable) {
                        failures.add(e)
                    }
                }
            }
            ready.await()
            start.countDown()
            pool.shutdown()
            while (!pool.isTerminated) Thread.sleep(10)
        } finally {
            pool.shutdownNow()
        }
        assertEquals("exactly one concurrent consumer must win", 1, successes.size)
        assertEquals(workers - 1, failures.size)
        assertTrue(failures.all { it is IllegalArgumentException })
        // The stored consumedAt is exactly the winning consumer's timestamp.
        assertEquals(successes.single(), storage.approvals.resolve(approval.id).consumedAt)
    }

    private fun seedToolCall(callId: String): ToolCallEntity {
        val session = storage.sessions.create("session-$callId", "proof session", null, null, 1L)
        val turn = storage.turns.start("turn-$callId-1", session.id, 2L)
        return storage.toolCalls.append("toolcall-$callId", turn.id, callId, "bash", "1", "{}", "PENDING")
    }

    private fun createApproval(
        id: String,
        toolCall: ToolCallEntity,
        scopeRef: String,
        sessionId: String,
        argsHash: String,
        createdAt: Long,
        expiresAt: Long,
    ) = storage.approvals.create(
        id,
        toolCall.id,
        binding(toolCall.callId, scopeRef = scopeRef, sessionId = sessionId, argsHash = argsHash),
        createdAt,
        expiresAt,
    )

    private fun binding(
        toolCallId: String,
        scopeRef: String,
        sessionId: String,
        argsHash: String,
    ) = ApprovalBinding(
        toolCallId = toolCallId,
        toolName = "bash",
        toolVersion = "1",
        schemaHash = "a".repeat(64),
        scopeRef = scopeRef,
        sessionId = sessionId,
        executionTarget = ExecutionTargetType.LOCAL_ANDROID,
        uiToken = "ui:approval-page:tok",
        argsHash = argsHash,
    )

    private companion object {
        const val DB_NAME = "approval-proof-lifecycle.db"
    }
}
