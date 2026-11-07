package com.helix.core.storage.repository

/** The stable NOT_PENDING reasons (doc 11 section 4: 状态已推进/已取消/版本变化/迟到 → 不生效). */
enum class NotPendingReason {
    /** The receipt id is unknown. */
    UNKNOWN,

    /** Already answered: the answer was consumed exactly once; this is a late or duplicate reply. */
    DUPLICATE_ANSWER,

    /** The question was cancelled (turn stop or explicit cancel) before the answer. */
    CANCELLED,

    /** A newer version of the same request superseded this receipt. */
    SUPERSEDED,

    /** The question's window elapsed before the answer. */
    EXPIRED,
}

/**
 * The repository's answer to a one-time receipt consumption (doc 11 section 4).
 * [Answered] carries the answer HASH only (the body is owned by the conversation message);
 * [NotPending] is a stable, terminal, non-credential outcome — it never creates or
 * touches an approval.
 */
sealed interface ReceiptResult {
    data class Answered(
        val id: String,
        val answerHash: String,
    ) : ReceiptResult

    data class NotPending(
        val id: String,
        val reason: NotPendingReason,
    ) : ReceiptResult
}
