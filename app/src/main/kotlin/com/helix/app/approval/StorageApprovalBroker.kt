package com.helix.app.approval

import com.helix.core.model.ApprovalDecision
import com.helix.core.model.Clock
import com.helix.core.policy.ApprovalMintOutcome
import com.helix.core.policy.ApprovalProof
import com.helix.core.policy.MintRejectionCode
import com.helix.core.storage.repository.ApprovalRepository
import com.helix.tools.framework.ApprovalAcquisition
import com.helix.tools.framework.ApprovalBroker
import com.helix.tools.framework.ApprovalRequest
import com.helix.tools.framework.CancelSignal

/**
 * Thrown by [StorageApprovalBroker.acquire] when the wait for the user's decision is
 * cancelled (the turn was stopped while the card was pending). It propagates OUT of
 * `ToolDispatcher.dispatch` (fail closed — AGENTS: no catch-all success); the chat service
 * turns it into the turn's stable CANCELLED terminal. The approval record stays PENDING in
 * storage (the user never decided; it expires with its 24h window) and can never mint.
 */
class ApprovalCancelledException(
    approvalId: String,
) : IllegalStateException("approval wait cancelled: $approvalId")

/** The three ways the blocking wait for a user decision can end. */
private sealed interface WaitOutcome {
    /** The user made a typed decision on this exact record. */
    data class Decision(
        val decision: ApprovalDecision,
    ) : WaitOutcome

    /** The turn was cancelled while the card was pending (no decision was made). */
    data object Cancelled : WaitOutcome

    /** The record's window elapsed before any decision (no decision was made). */
    data object Expired : WaitOutcome
}

/**
 * One pending approval's decision slot. Deliberately simple and portable: the Android SDK
 * has no `Object.wait`/`notify` and its queue/stub condition APIs are not uniform, so the
 * slot is a plain locked cell. The dispatcher's poll loop ([POLL_MILLIS]) re-checks the
 * slot, the turn-level [CancelSignal] carried by the request AND the card-level
 * [cancelled] set, and the record's window on every tick — the user's tap (or the turn
 * stop) reaches the dispatch thread within one poll (<= [POLL_MILLIS] ms), and the thread
 * is never interrupted (it is a shared worker).
 */
private class DecisionWaiter {
    private val lock = Any()

    fun offer(decision: ApprovalDecision) {
        synchronized(lock) {
            if (this.decision == null) {
                this.decision = decision
            }
        }
    }

    /** Returns the recorded decision (clearing it) or null when the user has not decided yet. */
    fun poll(): ApprovalDecision? =
        synchronized(lock) {
            val d = decision
            if (d != null) {
                decision = null
            }
            d
        }

    private var decision: ApprovalDecision? = null
}

/**
 * The production [ApprovalBroker] (roadmap HXA-036): the storage-backed, UI-decided
 * implementation of the HXA-035 port.
 *
 * Flow per `acquire`:
 * 1. create the PENDING record with the FULL [com.helix.core.policy.ApprovalBinding] hash
 *    and the 24h window (the HXA-034 repository cap — an approval is per-call and bounded,
 *    never permanent);
 * 2. publish the card to the confirmation surface via [cardSink] (the chat timeline); a
 *    sink that throws propagates — a call that cannot be shown cannot be approved;
 * 3. WAIT for the user's typed decision on this exact record (polling [POLL_MILLIS] so a
 *    turn cancellation or the window expiry can break the wait);
 * 4. map the outcome: `APPROVED` -> MINT (read-only, HXA-034) — only an APPROVED +
 *    unexpired + unconsumed record can ever produce [ApprovalAcquisition.Approved];
 *    `DENIED` -> [ApprovalAcquisition.Denied] (audit-only, never a credential);
 *    expiry -> [ApprovalAcquisition.Rejected] with [MintRejectionCode.EXPIRED] (NOT a user
 *    denial — the dispatcher's stable APPROVAL_EXPIRED code, no same-turn denial entry);
 *    cancellation -> [ApprovalCancelledException].
 *
 * The user action ([decide]) writes the decision IMMEDIATELY — the decision is an audited
 * fact the moment the user taps, independent of the dispatcher thread's lifecycle (if the
 * turn is cancelled after the tap, the row is already decided and still expires, but it
 * can never silently become "no decision happened").
 *
 * Invariants (ADR-0005 / doc 02 section 8.1): no auto-approve path exists in this class —
 * the only way to [ApprovalAcquisition.Approved] is the user's typed decision on the
 * pending record, minted through the HXA-034 guards. Profile switches, permissions and
 * Root grants are not read here at all, so they cannot influence a pending decision
 * (roadmap HXA-036 test: 切换 Profile 不改变待审批决定).
 */
class StorageApprovalBroker(
    private val approvals: ApprovalRepository,
    private val clock: Clock,
    private val idGenerator: () -> String,
    private val cardSink: (approvalId: String, request: ApprovalRequest) -> Unit,
) : ApprovalBroker {
    private val lock = Any()
    private val waits = HashMap<String, DecisionWaiter>()
    private val cancelled = HashSet<String>()

    // The card sink is an arbitrary UI callback: ANY failure there means the card cannot
    // be shown, so the catch is deliberately broad (fail closed + orphan-slot cleanup).
    @Suppress("TooGenericExceptionCaught")
    override fun acquire(request: ApprovalRequest): ApprovalAcquisition {
        val now = clock.now().toEpochMilli()
        val approvalId = idGenerator()
        approvals.create(
            id = approvalId,
            toolCallId = request.binding.toolCallId,
            binding = request.binding,
            createdAt = now,
            expiresAt = now + ApprovalRepository.MAX_APPROVAL_TTL_MILLIS,
        )
        // Register the wait slot BEFORE publishing the card: a decision that arrived
        // before the slot existed would be lost (decide() no-ops on an unknown id) — and
        // a lost decision is a dispatch that can only end in window expiry.
        val wait = DecisionWaiter()
        synchronized(lock) {
            waits[approvalId] = wait
        }
        try {
            cardSink(approvalId, request)
        } catch (e: Exception) {
            // A card that cannot be rendered cannot be approved: fail closed and leave no
            // orphan wait slot (the PENDING record stays and expires with its window).
            synchronized(lock) {
                waits.remove(approvalId)
            }
            throw e
        }
        try {
            return acquisitionFor(approvalId, waitForDecision(approvalId, wait, request.cancel))
        } finally {
            synchronized(lock) {
                waits.remove(approvalId)
                cancelled.remove(approvalId)
            }
        }
    }

    /** Maps the wait outcome onto the broker's typed answer (only [ApprovalAcquisition.Approved]
     * is a credential; a stop is not a decision). */
    private fun acquisitionFor(
        approvalId: String,
        outcome: WaitOutcome,
    ): ApprovalAcquisition =
        when (outcome) {
            is WaitOutcome.Decision -> {
                when (outcome.decision) {
                    ApprovalDecision.APPROVED -> mintProof(approvalId)
                    ApprovalDecision.DENIED -> ApprovalAcquisition.Denied
                }
            }

            WaitOutcome.Expired -> {
                ApprovalAcquisition.Rejected(MintRejectionCode.EXPIRED)
            }

            WaitOutcome.Cancelled -> {
                throw ApprovalCancelledException(approvalId)
            }
        }

    /**
     * Mints with the clock at MINT TIME, not at wait start: the HXA-034 guard is
     * "APPROVED + unexpired + unconsumed" and "unexpired" is evaluated when the proof is
     * created. A tap that lands in the poll gap after the window closed must fail closed
     * (EXPIRED), never mint a stale credential.
     */
    private fun mintProof(approvalId: String): ApprovalAcquisition =
        when (val mint = approvals.mint(approvalId, clock.now().toEpochMilli())) {
            is ApprovalMintOutcome.Minted -> ApprovalAcquisition.Approved(mint.proof)
            is ApprovalMintOutcome.Rejected -> ApprovalAcquisition.Rejected(mint.code)
        }

    /**
     * The user action from the approval card (UI -> chat service -> here). Records the
     * typed decision on the exact record (one-time, HXA-034) and unblocks the waiting
     * dispatcher. Called with a stale id (card outlived its record) fails closed with an
     * `IllegalArgumentException` from the repository — the card shows a stable error.
     */
    fun decide(
        approvalId: String,
        decision: ApprovalDecision,
    ) {
        approvals.decide(approvalId, decision, clock.now().toEpochMilli())
        val wait =
            synchronized(lock) {
                waits[approvalId]
            }
        if (wait != null) {
            wait.offer(decision)
        }
    }

    /**
     * Cancels the pending wait for [approvalId] (turn stop). The waiting [acquire] throws
     * [ApprovalCancelledException] within one poll; the record stays PENDING (the user
     * never decided) and expires with its window.
     */
    fun cancel(approvalId: String) {
        synchronized(lock) {
            cancelled.add(approvalId)
        }
    }

    override fun consume(proof: ApprovalProof) {
        val now = clock.now().toEpochMilli()
        approvals.consume(proof, now, now)
    }

    @Suppress("ReturnCount") // one return per terminal condition of the wait (cancel, expiry, decision, interrupt)
    private fun waitForDecision(
        approvalId: String,
        wait: DecisionWaiter,
        cancel: CancelSignal,
    ): WaitOutcome {
        while (true) {
            val isCancelled =
                cancel.isCancelled() ||
                    synchronized(lock) {
                        cancelled.contains(approvalId)
                    }
            if (isCancelled) {
                return WaitOutcome.Cancelled
            }
            // The record's own expiry is the hard bound: once now >= expiresAt there is no
            // decision to wait for. No decision row is written (the user never decided);
            // the record stays PENDING and simply expires (the HXA-034 guards make it
            // un-mintable regardless).
            val entity = approvals.resolve(approvalId)
            if (clock.now().toEpochMilli() >= entity.expiresAt) {
                return WaitOutcome.Expired
            }
            val decision = wait.poll()
            if (decision != null) {
                return WaitOutcome.Decision(decision)
            }
            try {
                Thread.sleep(POLL_MILLIS)
            } catch (e: InterruptedException) {
                // The worker was interrupted (process shutdown / scope teardown): stop
                // waiting and fail closed — the turn is being torn down anyway.
                Thread.currentThread().interrupt()
                return WaitOutcome.Cancelled
            }
        }
    }

    private companion object {
        const val POLL_MILLIS = 500L
    }
}
