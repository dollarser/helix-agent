package com.helix.app.chat

import com.helix.core.storage.entity.SessionEntity

/**
 * Decides which session a turn start runs in (research doc section 34; HX2-01).
 *
 * The unified [com.helix.core.agent.AgentRuntime.submit] addresses a turn to a SPECIFIC session.
 * When that session is explicit, the turn must run in exactly that session: if it cannot be
 * resolved (deleted or corrupt between the request and the start), the start is refused — it must
 * NEVER silently fall back to the open session, which would run the request in a different session
 * than its caller addressed. Only a start with NO explicit session (the in-app send path) falls
 * back to the open session.
 *
 * Pure (no storage / service reference) so it is unit-testable on the JVM, where the heavy
 * [ChatService] (concrete Room-backed deps, no Robolectric) cannot be constructed.
 */
internal object TurnSessionResolver {
    fun resolve(
        explicitSessionId: String?,
        openSession: SessionEntity?,
        resolveSession: (String) -> SessionEntity?,
    ): SessionEntity? =
        if (explicitSessionId == null) {
            openSession
        } else {
            // An explicit session that cannot be resolved is a refusal (null), never a fall-back to
            // [openSession] — the caller addressed a specific session and the turn must not run in
            // a different one.
            resolveSession(explicitSessionId)
        }
}
