package com.helix.app.tool

import com.helix.tools.framework.ApprovalRequest

/**
 * Breaks the construction cycle between the [com.helix.app.approval.StorageApprovalBroker]
 * (built by the container before the chat service) and the chat service that renders the
 * broker's pending approval cards.
 *
 * The broker delivers [deliver] while a dispatch waits for the user; before the chat
 * service installs [sink] (at the end of container construction) a delivery fails closed:
 * it throws, the dispatch propagates the failure (a card that cannot be rendered cannot be
 * approved — AGENTS: no catch-all success). In practice the sink is installed at startup,
 * so this path only guards a genuinely broken wiring.
 */
class ApprovalCardSinkHolder {
    @Volatile
    var sink: ((approvalId: String, request: ApprovalRequest) -> Unit)? = null

    fun deliver(
        approvalId: String,
        request: ApprovalRequest,
    ) {
        val s = sink
        if (s == null) {
            error("approval card sink not installed: the card for $approvalId cannot be rendered")
        }
        s(approvalId, request)
    }
}
