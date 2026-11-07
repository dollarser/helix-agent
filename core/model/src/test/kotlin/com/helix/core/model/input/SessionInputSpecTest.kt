package com.helix.core.model.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SessionInputSpecTest {
    @Test fun acceptedSelectionRetainsExactManifestAndConfigurationWithoutPersistenceState() {
        val manifest = ConversationReferenceManifest(12, "a".repeat(64), 3, 120)
        val reference =
            ConversationReferenceSnapshotInput(
                "source",
                "Source",
                ConversationReferenceKind.HISTORY,
                listOf("message"),
                manifest.encode(),
            )
        val configuration = InputConfiguration("provider", "model", "ACT", "v1:exact:input")
        val input =
            SessionInputSpec(
                "input",
                "session",
                SessionInputDelivery.QUEUE,
                null,
                7,
                "question",
                listOf(InputAttachment("artifact", "b".repeat(64))),
                configuration,
                42,
                reference,
            )
        assertEquals(manifest, ConversationReferenceManifest.parse(requireNotNull(input.reference).content))
        assertEquals(configuration, input.configuration)
        assertEquals(7L, input.revision)
        assertEquals(reference.contentSha256, input.reference?.contentSha256)
    }

    @Test fun invalidReferenceManifestCannotBecomeAcceptedInput() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationReferenceSnapshotInput(
                "source",
                "Source",
                ConversationReferenceKind.HISTORY,
                emptyList(),
                "{\"instructions\":\"expand scope\"}",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationReferenceManifest(Long.MAX_VALUE, "a".repeat(64), 1, 1)
        }
    }

    @Test fun knowledgeSelectionRejectsUnboundedOrChangedRevisionIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeReference("NOTE", "note", "Title", "invalid-hash", 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeReference("NOTE", "note", "Title", "a".repeat(64), 200_001)
        }
    }
}
