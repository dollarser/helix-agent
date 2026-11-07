package com.helix.app.infrastructure.chat

import com.helix.core.model.input.ConversationReferenceKind
import com.helix.core.model.input.ConversationReferenceManifest
import com.helix.core.model.input.ConversationReferenceSnapshotInput
import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.application.ConversationReferences

/** Captures metadata only; tools must revalidate the fixed source revision before reading. */
internal class ConversationReferenceResolver(
    private val storage: HelixStorage,
) : ConversationReferences {
    override fun prepare(
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

    override fun matches(reference: ConversationReferenceSnapshotInput): Boolean =
        try {
            val manifest = ConversationReferenceManifest.parse(reference.content)
            var matches = false
            storage.withTransaction {
                matches =
                    storage.sessionHistory.captureReference(reference.sourceSessionId, manifest.throughSequence) ==
                    manifest
            }
            matches
        } catch (_: IllegalArgumentException) {
            false
        }
}
