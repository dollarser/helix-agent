package com.helix.app.knowledge

import com.helix.core.storage.knowledge.CollectedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The still-supported Agent retrieval keeps query bounds and ranking coverage after removing standalone Q&A. */
class KnowledgeRetrievalTest {
    @Test fun englishAndChineseQueriesFindMultipleRelevantSources() {
        fun source(
            id: String,
            body: String,
        ) = CollectedSource(id, id, 1, "fixture", "", body, state = "READY")
        val sources =
            listOf(
                source("a", "Harbor budget 137. 项目预算137。"),
                source("b", "Harbor owner TEAM_SYNTH_42. 项目负责人。"),
                source("c", "Unrelated Zephyr source"),
            )
        val english = KnowledgeRetrieval.rank(sources, KnowledgeRetrieval.terms("Harbor budget and owner"))
        assertEquals(setOf("a", "b"), english.map { it.id }.toSet())
        val chinese = KnowledgeRetrieval.rank(sources, KnowledgeRetrieval.terms("预算和负责人分别是什么"))
        assertTrue(chinese.map { it.id }.containsAll(listOf("a", "b")))
    }

    @Test fun queryTermsRemainBoundedAndSupportChinese() {
        assertEquals(listOf("harbor", "budget", "owner"), KnowledgeRetrieval.terms("What is Harbor budget and owner?"))
        assertTrue(KnowledgeRetrieval.terms("预算和负责人分别是什么？").containsAll(listOf("预算", "负责")))
        assertTrue(KnowledgeRetrieval.terms("word ".repeat(200)).size <= 24)
        assertTrue(KnowledgeRetrieval.terms("!? ").isEmpty())
    }

    @Test fun generatedSummariesDoNotBecomeEvidenceAndRankingRemainsBounded() {
        val source = CollectedSource("one", "Synthetic", 1, "fixture", "", "Harbor budget 137", state = "READY")
        val other = source.copy(id = "two", rawText = "Unrelated", summary = "Harbor budget")
        assertEquals(listOf(source), KnowledgeRetrieval.rank(listOf(other, source), KnowledgeRetrieval.terms("Harbor")))
        val all = (0..30).map { source.copy(id = "$it") }
        assertEquals(16, KnowledgeRetrieval.rank(all, listOf("budget")).size)
    }
}
