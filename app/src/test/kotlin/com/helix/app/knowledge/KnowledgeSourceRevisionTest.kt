package com.helix.app.knowledge

import com.helix.core.storage.knowledge.CollectedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KnowledgeSourceRevisionTest {
    @Test fun originalAndCoverageChangesInvalidateAnUnchangedPreview() {
        val source = CollectedSource("id", "title", 1, "fixture", "", "text")
        assertEquals(knowledgeHash("text"), knowledgeSourceRevision(source))
        val attached = source.copy(filesJson = "[{\"sha256\":\"original-a\"}]")
        assertNotEquals(knowledgeSourceRevision(source), knowledgeSourceRevision(attached))
        assertNotEquals(
            knowledgeSourceRevision(attached),
            knowledgeSourceRevision(
                attached.copy(
                    filesJson = "[{\"sha256\":\"original-b\"}]",
                ),
            ),
        )
        assertNotEquals(knowledgeSourceRevision(attached), knowledgeSourceRevision(attached.copy(extraction = "[]")))
        assertEquals(
            knowledgeSourceRevision(
                attached.copy(filesJson = "[{\"sha256\":\"original-a\",\"reference\":\"content://old/file\"}]"),
            ),
            knowledgeSourceRevision(
                attached.copy(filesJson = "[{\"sha256\":\"original-a\",\"reference\":\"unavailable:backup:id:0\"}]"),
            ),
        )
    }
}
