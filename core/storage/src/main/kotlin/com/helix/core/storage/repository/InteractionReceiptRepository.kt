package com.helix.core.storage.repository

import com.helix.core.storage.dao.InteractionReceiptDao
import com.helix.core.storage.entity.InteractionReceiptEntity

/**
 * Structured user questions with one-time receipts (doc 11 section 4, roadmap HXA-037).
 *
 * Invariants:
 * - a question is bound to session/turn/[requestId]/[version]/expiry; a newer version of
 *   the same requestId supersedes the older pending receipts (version change → NOT_PENDING);
 * - the answer is consumed EXACTLY ONCE (SQL state guard); late, duplicate, cancelled,
 *   superseded or expired answers all return a stable [ReceiptResult.NotPending] and never
 *   overwrite a state that has already advanced;
 * - a receipt is NOT an approval proof: no receipt operation creates, decides, mints or
 *   consumes anything in the `approvals` table — answering a question can never grant a
 *   tool call (AGENTS: 提问不代替审批).
 */
class InteractionReceiptRepository(
    private val dao: InteractionReceiptDao,
) {
    /** One pending question: the bounded fields the [open] call binds. */
    data class ReceiptRequest(
        val id: String,
        val sessionId: String,
        val turnId: String,
        val requestId: String,
        val version: Int,
        val questionSummary: String,
        val createdAt: Long,
        val ttlMillis: Long,
    )

    /**
     * Opens a pending receipt. [ReceiptRequest.ttlMillis] is bounded by [MAX_RECEIPT_TTL_MILLIS]
     * (1 h): a structured question is ephemeral — a stale question must expire, not linger.
     * [ReceiptRequest.questionSummary] is the bounded redacted summary (the question body
     * itself stays with the conversation; no sensitive content is duplicated here).
     */
    fun open(request: ReceiptRequest): InteractionReceiptEntity {
        require(request.sessionId.isNotBlank() && request.turnId.isNotBlank() && request.requestId.isNotBlank()) {
            "receipt needs a non-blank session/turn/request binding"
        }
        require(request.version >= 1) { "version must be >= 1" }
        require(request.createdAt >= 0) { "createdAt must be >= 0" }
        require(request.ttlMillis in 1..MAX_RECEIPT_TTL_MILLIS) {
            "ttl must be in 1..$MAX_RECEIPT_TTL_MILLIS ms: ${request.ttlMillis}"
        }
        require(request.questionSummary.length <= MAX_QUESTION_SUMMARY_LENGTH) {
            "questionSummary exceeds $MAX_QUESTION_SUMMARY_LENGTH chars"
        }
        val entity =
            InteractionReceiptEntity(
                id = request.id,
                sessionId = request.sessionId,
                turnId = request.turnId,
                requestId = request.requestId,
                version = request.version,
                questionSummary = request.questionSummary,
                state = "PENDING",
                createdAt = request.createdAt,
                expiresAt = request.createdAt + request.ttlMillis,
                answerHash = null,
                answeredAt = null,
            )
        dao.insert(entity)
        // A newer version supersedes older pending receipts of the same request.
        dao.supersedeOlder(request.requestId, request.version)
        return entity
    }

    /**
     * Consumes the receipt with the user's answer. Returns [ReceiptResult.Answered] exactly
     * once; every other path returns a stable [ReceiptResult.NotPending] with the reason.
     */
    fun answer(
        id: String,
        answerHash: String,
        now: Long,
    ): ReceiptResult {
        require(answerHash.isNotBlank()) { "answerHash must not be blank" }
        require(now >= 0) { "now must be >= 0" }
        if (dao.answer(id, answerHash, now, now) == 1) {
            return ReceiptResult.Answered(id, answerHash)
        }
        return notPending(id)
    }

    /** Cancels a pending question (turn stop); already-advanced states are not touched. */
    fun cancel(id: String): ReceiptResult {
        if (dao.cancel(id) == 1) {
            return ReceiptResult.NotPending(id, NotPendingReason.CANCELLED)
        }
        return notPending(id)
    }

    /** The session's pending (unexpired) receipts, oldest first — the UI's open questions. */
    fun pending(
        sessionId: String,
        now: Long,
    ): List<InteractionReceiptEntity> = dao.pending(sessionId, now)

    /** Bounded newest-first load for the audit/interaction history (the page never loads all). */
    fun recent(
        sessionId: String,
        limit: Int,
    ): List<InteractionReceiptEntity> {
        require(limit in 1..MAX_RECENT_LIMIT) { "recent limit must be in 1..$MAX_RECENT_LIMIT" }
        return dao.recent(sessionId, limit)
    }

    private fun notPending(id: String): ReceiptResult {
        val entity = dao.byId(id)
        val reason =
            when {
                entity == null -> NotPendingReason.UNKNOWN
                entity.state == "ANSWERED" -> NotPendingReason.DUPLICATE_ANSWER
                entity.state == "CANCELLED" -> NotPendingReason.CANCELLED
                entity.state == "SUPERSEDED" -> NotPendingReason.SUPERSEDED
                else -> NotPendingReason.EXPIRED
            }
        return ReceiptResult.NotPending(id, reason)
    }

    companion object {
        /** Hard cap for a structured question's window (1 h): ephemeral, must expire. */
        const val MAX_RECEIPT_TTL_MILLIS = 60L * 60L * 1000L

        /** Redaction bound: the stored summary never carries a sensitive body. */
        const val MAX_QUESTION_SUMMARY_LENGTH = 512

        private const val MAX_RECENT_LIMIT = 1000
    }
}
