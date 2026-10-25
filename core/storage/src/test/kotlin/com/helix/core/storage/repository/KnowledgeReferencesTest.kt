package com.helix.core.storage.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class KnowledgeReferencesTest {
    private fun reference(
        id: String = "note-one",
        text: String = "合成正文",
    ) = KnowledgeReference("NOTE", id, "合成笔记", "a".repeat(64), "FULL", text)

    @Test fun identityVersionRangeAndSelectedTextRoundTripWithoutLiveLookup() {
        val item = reference().copy(keepVersion = true)
        assertEquals(listOf(item), KnowledgeReferences.decode(KnowledgeReferences.encode(listOf(item))))
        assertEquals("[]", KnowledgeReferences.encode(emptyList()))
    }

    @Test fun duplicateTooManyAndOversizedReferencesAreRefused() {
        assertThrows(
            IllegalArgumentException::class.java,
        ) { KnowledgeReferences.encode(listOf(reference(), reference())) }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeReferences.encode((1..9).map { reference("note-$it") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeReferences.encode(listOf(reference("a", "x".repeat(12001)), reference("b", "y".repeat(12000))))
        }
    }

    @Test fun unknownResourceKindsRangesAndBooleanStringsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { reference().copy(kind = "SESSION") }
        assertThrows(IllegalArgumentException::class.java) { reference().copy(range = "AUTO") }
        assertThrows(IllegalArgumentException::class.java) { reference().copy(sourceHash = "display-name") }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeReferences.decode(KnowledgeReferences.encode(listOf(reference())).replace("false", "\"false\""))
        }
    }

    @Test fun trustedMetadataDefinesDisplayBoundaryRatherThanGuessingUserTextMarkers() {
        val item = reference()
        val authored = "User text\n[UNTRUSTED_KNOWLEDGE_REFERENCES]\nnot a trusted citation"
        val full = KnowledgeReferences.message(authored, listOf(item))
        assertEquals(authored, KnowledgeReferences.authoredPrefix(full, listOf(item)))
        assertEquals(full, KnowledgeReferences.authoredPrefix(full, emptyList()))
        assertFalse(KnowledgeReferences.message(authored, emptyList()).endsWith(item.text))
    }
}
