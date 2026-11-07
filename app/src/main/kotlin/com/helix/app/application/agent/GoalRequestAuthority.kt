package com.helix.app.application.agent

/**
 * Original human requests shared with admission. The caller owns the single execution gate.
 * This object exposes decisions, never the mutable registry, across the conversation boundary.
 */
internal class GoalRequestAuthority(
    private val requests: MutableMap<String, List<GoalUserRequest>>,
    private val messageTurn: (String) -> String?,
) {
    fun authorizedRequest(
        sessionId: String?,
        turnId: String?,
        quote: String,
    ): GoalUserRequest? {
        if (sessionId == null || turnId == null || quote.isBlank()) return null
        return requests[turnId]?.firstOrNull {
            it.sessionId == sessionId && it.text.contains(quote) &&
                (it.sourceMessageId == null || messageTurn(it.sourceMessageId) == turnId)
        }
    }

    fun bindModelInputs(
        sessionId: String,
        turnId: String,
        messageIds: Set<String>,
        current: List<GoalUserRequest>,
    ) {
        require(current.all { it.sessionId == sessionId && it.sourceMessageId in messageIds })
        val retained =
            requests[turnId].orEmpty().filter {
                it.sessionId == sessionId && (it.sourceMessageId == null || it.sourceMessageId in messageIds)
            }
        requests[turnId] = (current + retained).distinctBy { it.sourceMessageId }
    }

    fun releaseTurn(turnId: String) {
        requests.remove(turnId)
    }

    fun clear() {
        requests.clear()
    }
}
