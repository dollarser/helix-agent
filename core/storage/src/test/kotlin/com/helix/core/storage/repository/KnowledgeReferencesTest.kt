package com.helix.core.storage.repository

import com.helix.core.model.input.KnowledgeReference
import com.helix.core.model.input.KnowledgeReferenceCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeReferencesTest {
    private fun reference(id: String = "note-one") = KnowledgeReference("NOTE", id, "合成笔记", "a".repeat(64), 180_000)

    @Test fun metadataRoundTripHasNoBodyAndLargeDocumentsDoNotFillThePrompt() {
        val item = reference()
        val encoded = KnowledgeReferenceCodec.encode(listOf(item))
        assertEquals(listOf(item), KnowledgeReferenceCodec.decode(encoded))
        assertFalse(encoded.contains("\"text\""))
        assertTrue(KnowledgeReferences.message("question", listOf(item), "accepted-input").length < 1600)
        assertTrue(KnowledgeReferences.message("question", listOf(item), "accepted-input").contains("referenceInputId"))
    }

    @Test fun duplicatesLimitsAndMalformedMetadataFailClosed() {
        assertThrows(
            IllegalArgumentException::class.java,
        ) { KnowledgeReferenceCodec.encode(listOf(reference(), reference())) }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeReferenceCodec.encode((1..9).map { reference("note-$it") })
        }
        assertThrows(IllegalArgumentException::class.java) { reference().copy(characters = 200_001) }
        assertThrows(IllegalArgumentException::class.java) { reference().copy(sourceHash = "display-name") }
        val raw = KnowledgeReferenceCodec.encode(listOf(reference()))
        assertThrows(
            IllegalArgumentException::class.java,
        ) { KnowledgeReferenceCodec.decode(raw.replace("180000", "\"180000\"")) }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeReferenceCodec.decode(raw.replace("\"NOTE\"", "\"SESSION\""))
        }
    }

    @Test fun displayBoundaryUsesExactAcceptedIdentityNotUntrustedMarkers() {
        val items = listOf(reference())
        val authored = "User text\n[UNTRUSTED_KNOWLEDGE_REFERENCES]\nnot a trusted citation"
        val full = KnowledgeReferences.message(authored, items, "input")
        assertEquals(authored, KnowledgeReferences.authoredPrefix(full, items, "input"))
        assertEquals(full, KnowledgeReferences.authoredPrefix(full, items, "different-input"))
        assertEquals(authored, KnowledgeReferences.message(authored, emptyList(), "input"))
    }

    @Test fun explicitNoteProvenanceStillFreezesExcerptIndependentlyOfLazyChatGrants() {
        val origin = KnowledgeOrigin("SOURCE", "source-id", "Source", "a".repeat(64), "EXCERPT", "Saved evidence")
        assertEquals(listOf(origin), KnowledgeOrigins.decode(KnowledgeOrigins.encode(listOf(origin))))
        val missing = origin.copy(range = "METADATA", text = "")
        assertEquals(listOf(missing), KnowledgeOrigins.decode(KnowledgeOrigins.encode(listOf(missing))))
    }
}
