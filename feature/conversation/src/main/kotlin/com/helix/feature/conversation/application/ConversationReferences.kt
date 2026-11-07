package com.helix.feature.conversation.application

import com.helix.core.model.input.ConversationReferenceKind
import com.helix.core.model.input.ConversationReferenceSnapshotInput

/** Captured conversation authority is a fixed source revision, never the latest available history. */
interface ConversationReferences {
    fun prepare(
        targetSessionId: String,
        sourceSessionId: String,
        kind: ConversationReferenceKind,
    ): ConversationReferenceSnapshotInput

    fun matches(reference: ConversationReferenceSnapshotInput): Boolean
}
