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
 * One pending approval's decision slot. A plain `Object.wait`/`notifyAll` rendezvous:
 * the UI thread ([StorageApprovalBroker.decide]) and the turn stop ([StorageApprovalBroker.cancel])
 * wake the waiting dispatch thread IMMEDIATELY — no polling. The wait is bounded by a
 * timed slice ([WAIT_SLICE_MILLIS]) so the expiry / cancel re-check stays periodic even
 * when no one notifies (the record window elapsing while the user is away). `notifyAll`
 * (not `notify`) because the waiter re-enters the loop after every wake: lost single
 * notifications are structurally impossible, and spurious wakes are re-checked.
 */
private class DecisionWaiter {
    private val monitor = Object()

    /**
     * Records the decision (first one wins — the record is one-time) and wakes the waiter.
     * Offer AND wake inside the same monitor: the waiter either sees the decision or is
     * woken, never neither (the classic lost-wakeup bug).
     */
    fun offer(decision: ApprovalDecision) {
        val monitor = monitor
        synchronized(monitor) {
            if (this.decision == null) {
                this.decision = decision
            }
            monitor.notifyAll()
        }
    }

    /** Wakes the waiter (turn stop); the waiter's cancel re-check ends the wait. */
    fun wake() {
        val monitor = monitor
        synchronized(monitor) {
            monitor.notifyAll()
        }
    }

    /**
     * Returns the recorded decision (clearing it), or null after blocking up to
     * [timeoutMillis] when the user has not decided yet. The interrupt state is restored
     * on timeout (the caller treats an interrupted worker as cancelled).
     */
    fun await(timeoutMillis: Long): ApprovalDecision? {
        // The deadline is fixed at ENTRY (one slice per caller request). After it elapses
        // with no recorded decision the wait MUST return null — the caller re-checks
        // cancel/expiry on its own loop. (Returning is mandatory: a null spin here would
        // burn a scheduler pool thread forever and leave its in-flight footprint
        // occupying a concurrency slot, starving every conflicting call in the process.)
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        val monitor = monitor
        while (true) {
            val result =
                synchronized(monitor) {
                    val recorded = decision
                    if (recorded == null) {
                        val remainingMillis = (deadline - System.nanoTime()) / 1_000_000L
                        if (remainingMillis > 0) monitor.wait(remainingMillis)
                        decision
                    } else {
                        decision = null
                        recorded
                    }
                }
            if (result != null) return result
            if ((deadline - System.nanoTime()) <= 0L) return null
        }
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
 * 3. WAIT for the user's typed decision on this exact record (woken immediately by the
 *    decision or a turn stop; a [WAIT_SLICE_MILLIS] slice keeps the expiry re-check alive);
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
                waits.remove(approvalId)
            }
        if (wait != null) {
            wait.offer(decision)
        }
    }

    /**
     * Cancels the pending wait for [approvalId] (turn stop). The waiting [acquire] throws
     * [ApprovalCancelledException] immediately (the cancel wakes its waiter); the record
     * stays PENDING (the user never decided) and expires with its window.
     *
     * A cancel for an id with NO live wait slot is a NO-OP (and records nothing): the
     * `cancelled` set is cleaned only by the acquire that registered the slot, so a
     * stale id — e.g. the chat service's last-active-card pointer after that card was
     * already decided — would leak a set entry for the whole process lifetime. This is
     * symmetric with [decide], which also acts only on ids it knows.
     */
    fun cancel(approvalId: String) {
        val wait =
            synchronized(lock) {
                val w = waits[approvalId]
                if (w != null) {
                    cancelled.add(approvalId)
                }
                w
            }
        wait?.wake()
    }

    override fun consume(proof: ApprovalProof) {
        val now = clock.now().toEpochMilli()
        approvals.consume(proof, now, now)
    }

    /**
     * Bounded technical retry (roadmap HXA-037): refund the spent proof and re-mint from
     * the SAME record — without presenting the confirmation surface again. Null when the
     * record can no longer mint (window elapsed between the failure and the retry) or the
     * refund guard failed; the dispatcher then ends the retry with the original failure.
     */
    override fun reMint(proof: ApprovalProof): ApprovalProof? {
        if (!approvals.refund(proof)) return null
        return when (val mint = approvals.mint(proof.approvalId, clock.now().toEpochMilli())) {
            is ApprovalMintOutcome.Minted -> mint.proof
            is ApprovalMintOutcome.Rejected -> null
        }
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
            val decision =
                try {
                    wait.await(WAIT_SLICE_MILLIS)
                } catch (e: InterruptedException) {
                    // The worker was interrupted (process shutdown / scope teardown): stop
                    // waiting and fail closed — the turn is being torn down anyway.
                    Thread.currentThread().interrupt()
                    return WaitOutcome.Cancelled
                }
            if (decision != null) {
                // Cancellation wins over a racing decision (M3 closeout review): the user
                // tapped approve in the same instant the turn stopped (stop() cancels the
                // wait AFTER the broker already wrote the decision row — both facts are
                // true, and the turn-level outcome is the honest one). The record keeps
                // its typed APPROVED and simply expires unconsumed; surfacing Decision
                // here would mint a proof the dispatcher will never consume and show the
                // card "approved" for a turn the user just stopped.
                val cancelledInRace =
                    cancel.isCancelled() ||
                        synchronized(lock) {
                            cancelled.contains(approvalId)
                        }
                return if (cancelledInRace) WaitOutcome.Cancelled else WaitOutcome.Decision(decision)
            }
        }
    }

    private companion object {
        /**
         * The expiry/cancel re-check slice. The wait is normally woken IMMEDIATELY by the
         * user's decision or a turn stop (notifyAll); the slice only bounds the no-wake
         * case (the window elapsing while no one is at the card).
         */
        const val WAIT_SLICE_MILLIS = 500L
    }
}
