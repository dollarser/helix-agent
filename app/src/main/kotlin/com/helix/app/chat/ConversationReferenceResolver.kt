package com.helix.app.chat

import com.helix.core.model.input.ConversationReferenceKind
import com.helix.core.model.input.ConversationReferenceSnapshotInput
import com.helix.core.storage.HelixStorage

/** Captures metadata only; tools must revalidate the fixed source revision before reading. */
internal class ConversationReferenceResolver(
    private val storage: HelixStorage,
) {
    fun prepare(
        targetSessionId: String,
        sourceSessionId: String,
        kind: ConversationReferenceKind,
    ): ConversationReferenceSnapshotInput {
        var snapshot: ConversationReferenceSnapshotInput? = null
        storage.withTransaction {
            require(targetSessionId != sourceSessionId) { "REFERENCE_SELF" }
            val source = storage.sessions.resolve(sourceSessionId)
            val manifest = storage.sessionHistory.captureReference(sourceSessionId)
            snapshot =
                ConversationReferenceSnapshotInput(sourceSessionId, source.title, kind, emptyList(), manifest.encode())
        }
        return requireNotNull(snapshot)
    }
}
