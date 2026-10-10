package com.helix.app.chat

import com.helix.app.approval.ApprovalCardUi

/** Live display cache only. Durable call state and broker records still authorize every decision. */
internal class NativeApprovalProjection {
    private data class Entry(
        val turnId: String,
        val callId: String,
        val card: ApprovalCardUi,
        val sequence: Long,
    )

    private val sequence =
        java.util.concurrent.atomic
            .AtomicLong()
    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun register(
        turnId: String,
        callId: String,
        card: ApprovalCardUi,
    ) {
        entries.putIfAbsent(card.approvalId, Entry(turnId, callId, card, sequence.getAndIncrement()))
    }

    fun forTurn(turnId: String): List<ApprovalCardUi> =
        entries.values
            .filter {
                it.turnId == turnId
            }.sortedBy { it.sequence }
            .map { it.card }

    fun claim(
        turnId: String,
        approvalId: String,
    ): ApprovalCardUi? {
        val entry = entries[approvalId]?.takeIf { it.turnId == turnId } ?: return null
        return if (entries.remove(approvalId, entry)) entry.card else null
    }

    fun callId(approvalId: String): String? = entries[approvalId]?.callId

    fun remove(approvalId: String) {
        entries.remove(approvalId)
    }

    fun finishTurn(turnId: String) {
        entries.entries.removeIf { it.value.turnId == turnId }
    }

    fun finishCall(callId: String) {
        entries.entries.removeIf { it.value.callId == callId }
    }
}
