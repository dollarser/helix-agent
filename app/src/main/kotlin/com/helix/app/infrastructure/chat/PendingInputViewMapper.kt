package com.helix.app.infrastructure.chat

import com.helix.core.storage.repository.SessionInputRecord
import com.helix.core.storage.repository.SessionInputState
import com.helix.feature.conversation.input.PendingInputView

/** Durable delivery state stays here; the composer receives only undelivered presentation facts. */
internal fun SessionInputRecord.toPendingInputView(): PendingInputView? =
    when (state) {
        SessionInputState.PENDING, SessionInputState.NEEDS_ATTENTION -> {
            PendingInputView(
                inputId = inputId,
                revision = revision,
                delivery = delivery,
                needsAttention = state == SessionInputState.NEEDS_ATTENTION,
                knowledgeReferences = knowledgeReferences,
            )
        }

        SessionInputState.APPENDED, SessionInputState.WITHDRAWN, SessionInputState.FAILED -> {
            null
        }
    }
