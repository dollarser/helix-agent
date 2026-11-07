package com.helix.core.model.citation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CitationTest {
    private val hash = "a".repeat(64)

    @Test fun exactRangesUseCodePointsAndNeverInterpretSourceText() {
        val citation = requireNotNull(Citation.parse("helix://knowledge/source/doc?revision=$hash&range=1:3"))
        assertEquals("😀中", citation.excerpt("A😀中文"))
        assertThrows(IllegalArgumentException::class.java) { citation.excerpt("A") }
        val message = requireNotNull(Citation.parse("helix://session/s/m?range=0:2&revision=$hash"))
        assertEquals("m", message.messageId)
        assertEquals("session", message.kind)
    }

    @Test fun malformedAndAuthorityBearingLinksCannotNavigate() {
        listOf(
            "helix:opaque",
            "https://knowledge/source/doc?revision=$hash&range=0:1",
            "helix://user@knowledge/source/doc?revision=$hash&range=0:1",
            "helix://knowledge/source/%2E%2E?revision=$hash&range=0:1",
            "helix://knowledge/source/doc?revision=$hash&range=0:20001",
            "helix://knowledge/source/doc?revision=$hash&range=-1:2",
            "helix://knowledge/source/doc?revision=$hash&range=0:1&revision=$hash",
            "helix://knowledge/source/doc?revision=$hash&range=0:1#run",
            "helix://oauth/source/doc?revision=$hash&range=0:1",
        ).forEach { assertNull(it, Citation.parse(it)) }
    }
}
