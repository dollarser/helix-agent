package com.helix.core.storage.repository

import com.helix.core.storage.SearchText
import com.helix.core.storage.dao.SessionSearchRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

/** Query semantics run against the actual SQL/schema in host SQLite and the Room device fixture. */
class SessionSearchRepositoryTest {
    private val dao = SearchProjectionFixture()
    private val repository = SessionSearchRepository(dao)

    private fun row(
        id: String = "s",
        title: Boolean = false,
        body: String? = "原文 Needle",
    ) = SessionSearchRow(id, "Title", 5, 1, 10, title, body)

    @Test fun literalUnicodeCaseFoldingPreservesOffsets() {
        assertEquals("i", SearchText.fold("ı"))
        assertEquals(SearchText.fold("I"), SearchText.fold("İ"))
        assertEquals("中文%_needle", SearchText.fold("中文%_Needle"))
        assertEquals("😀中文".length, SearchText.fold("😀中文").length)
    }

    @Test fun blankQueryDoesNotReadTheDatabase() {
        assertEquals(SessionSearchResult("", emptyList(), false), repository.search("  "))
        assertNull(dao.query)
    }

    @Test fun matchKindsArchiveActivityAndOriginalSnippetArePreserved() {
        dao.results = listOf(row("both", true), row("title", true, null), row("body"))
        val result = repository.search("  NEEDLE  ")
        assertEquals("needle" to 51, dao.query)
        assertEquals("NEEDLE", result.query)
        assertEquals(setOf(SessionSearchMatchKind.TITLE, SessionSearchMatchKind.MESSAGE), result.hits[0].matchedKinds)
        assertEquals(setOf(SessionSearchMatchKind.TITLE), result.hits[1].matchedKinds)
        assertEquals(setOf(SessionSearchMatchKind.MESSAGE), result.hits[2].matchedKinds)
        assertEquals("原文 Needle", result.hits[0].messageSnippet)
        assertTrue(result.hits.all { it.isArchived && it.lastActivityAt == 10L })
        assertFalse(result.truncated)
    }

    @Test fun noMatchIsNotMistakenForTruncation() {
        assertEquals(SessionSearchResult("absent", emptyList(), false), repository.search("absent"))
    }

    @Test fun lookaheadDistinguishesExactLimitFromAdditionalMatches() {
        dao.results = listOf(row("one"), row("two"))
        assertTrue(repository.search("x", maxHits = 1).truncated)
        assertEquals(1, repository.search("x", maxHits = 1).hits.size)
        assertFalse(repository.search("x", maxHits = 2).truncated)
    }

    @Test fun cancellationBeforeQueryDoesNotReadOrPublish() {
        assertThrows(CancellationException::class.java) {
            repository.search("x", checkActive = { throw CancellationException() })
        }
        assertNull(dao.query)
    }

    @Test fun cancellationAfterQueryDoesNotPublishStaleHits() {
        dao.results = listOf(row())
        var checks = 0
        assertThrows(CancellationException::class.java) {
            repository.search("x", checkActive = { if (++checks == 2) throw CancellationException() })
        }
        assertEquals("x" to 51, dao.query)
    }

    @Test fun databaseFailurePropagatesInsteadOfReportingNoMatches() {
        val failure = IllegalStateException("query failed")
        dao.failure = failure
        assertSame(failure, assertThrows(IllegalStateException::class.java) { repository.search("x") })
    }

    @Test fun invalidBoundsFailBeforeQuery() {
        listOf(0, -1, 201).forEach { bound ->
            assertThrows(IllegalArgumentException::class.java) { repository.search("x", maxHits = bound) }
        }
        assertNull(dao.query)
    }
}
