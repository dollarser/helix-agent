package com.helix.app.knowledge

import com.helix.core.storage.knowledge.CollectedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeRetrievalTest {
    @Test fun questionTermsAreBoundedAndSupportChineseWithoutSendingAnything() {
        assertEquals(listOf("harbor", "budget", "owner"), KnowledgeRetrieval.terms("What is Harbor budget and owner?"))
        assertTrue(KnowledgeRetrieval.terms("预算和负责人分别是什么？").containsAll(listOf("预算", "负责", "责任").take(2)))
        assertTrue(KnowledgeRetrieval.terms("word ".repeat(200)).size <= 24)
        assertTrue(KnowledgeRetrieval.terms("!? ").isEmpty())
    }

    @Test fun unrelatedSourcesAndGeneratedSummariesAreNotEvidence() {
        val terms = KnowledgeRetrieval.terms("Harbor budget")
        val source = source("one", "Harbor budget is 137")
        val other = source("two", "Unrelated").copy(summary = "Harbor budget")
        assertEquals(listOf(source), KnowledgeRetrieval.rank(listOf(other, source), terms))
    }

    @Test fun excerptsReachDeepHitsAndShowBoundsWithoutBreakingUnicode() {
        val source = source("one", "前言😀".repeat(3000) + "预算是137。" + "末尾😀".repeat(3000))
        val result = KnowledgeRetrieval.evidence(source, "预算", 3000)
        assertTrue(result.excerpt.contains("预算是137"))
        assertTrue(result.excerpt.contains("characters"))
        assertTrue(result.excerpt.length < 3100)
        assertFalse(result.excerpt.contains("\uFFFD"))
        assertEquals(knowledgeHash(source.rawText), result.contentHash)
        assertTrue(result.completeness.contains("not full source"))
    }

    @Test fun multipleTermsYieldMultipleLocalWindowsAndStableRanking() {
        val text = "budget 137 " + "filler ".repeat(2000) + "owner TEAM_SYNTH_42"
        val result = KnowledgeRetrieval.evidence(source("one", text), "budget owner", 3000)
        assertTrue(result.excerpt.contains("budget 137"))
        assertTrue(result.excerpt.contains("owner TEAM_SYNTH_42"))
        val all = (0..30).map { source("$it", "budget") }
        assertEquals(16, KnowledgeRetrieval.rank(all, listOf("budget")).size)
    }

    private fun source(
        id: String,
        text: String,
    ) = CollectedSource(id, "Synthetic", 1, "fixture", "", text, state = "READY")
}
