package com.helix.app.eval

import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.application.ChatService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A cancelled test coroutine must commit Stop before its process exits or recovery can inspect it. */
internal suspend fun stopOwnedCase(
    storage: HelixStorage,
    chat: ChatService,
    session: String,
) {
    withContext(NonCancellable) {
        // Include an interrupted original and any already admitted inspection, retaining their identities.
        storage.turns
            .listBySession(session)
            .asReversed()
            .forEach { chat.stopTurn(it.id) }
        check(
            storage.turns.listBySession(session).all {
                it.state in setOf("COMPLETED", "FAILED", "CANCELLED") ||
                    it.pauseRequestedAt != null || it.errorCode == "USER_STOP"
            },
        ) { "OWNED_CASE_STOP_NOT_DURABLE" }
    }
}

/** Every cleanup step still runs when a prior step or evidence writer fails. */
internal suspend fun finishOwnedCase(
    storage: HelixStorage,
    chat: ChatService,
    session: String,
    closeRequests: () -> Unit,
    evidence: () -> Unit,
    restore: (Boolean) -> Unit,
) {
    withContext(NonCancellable) {
        var stopped = false
        try {
            try {
                closeRequests()
            } finally {
                stopOwnedCase(storage, chat, session)
                stopped = true
            }
        } finally {
            try {
                evidence()
            } finally {
                restore(stopped)
            }
        }
    }
}
