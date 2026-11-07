package com.helix.core.model.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class KnowledgeReferenceCodecTest {
    private fun reference(id: String = "note-one") =
        KnowledgeReference("NOTE", id, "Selected revision", "a".repeat(64), 180_000)

    @Test fun selectedReferencesRoundTripWithoutStorage() {
        val items = listOf(reference(), reference("note-two"))
        val encoded = KnowledgeReferenceCodec.encode(items)
        assertEquals(items, KnowledgeReferenceCodec.decode(encoded))
        assertEquals(encoded, KnowledgeReferenceCodec.encode(KnowledgeReferenceCodec.decode(encoded)))
        assertEquals(emptyList<KnowledgeReference>(), KnowledgeReferenceCodec.decode("[]"))
    }

    @Test fun duplicateIdentitiesAndOversizedSelectionsFailClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeReferenceCodec.encode(listOf(reference(), reference().copy(title = "Other title")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeReferenceCodec.encode((1..9).map { reference("note-$it") })
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeReferenceCodec.decode(" ".repeat(12_001))
        }
    }

    @Test fun unknownFieldsAndWrongScalarTypesAreNotAcceptedAsReferences() {
        val encoded = KnowledgeReferenceCodec.encode(listOf(reference()))
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeReferenceCodec.decode(encoded.replace("\"characters\":180000", "\"characters\":\"180000\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeReferenceCodec.decode(encoded.replace("\"kind\":\"NOTE\"", "\"kind\":\"SESSION\""))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeReferenceCodec.decode(encoded.replace("{", "{\"body\":\"untrusted\","))
        }
    }
}
