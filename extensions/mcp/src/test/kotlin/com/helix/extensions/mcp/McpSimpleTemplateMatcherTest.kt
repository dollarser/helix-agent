package com.helix.extensions.mcp

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpSimpleTemplateMatcherTest {
    @Test
    fun scalarExpansionsIncludeEmptyAndWholeEncodedOctets() =
        runBlocking {
            val template = "memo://item/{id}"
            for (value in listOf("", "azAZ09-._~", "a%20b", "%2F%3f%23", "%E4%B8%AD")) {
                assertTrue(value, matchesSimpleTemplate(template, "memo://item/$value"))
            }
            val invalid =
                listOf(
                    "/",
                    "?",
                    "#",
                    ":",
                    "@",
                    "&",
                    "=",
                    "+",
                    ",",
                    ";",
                    "!",
                    "$",
                    "'",
                    "(",
                    ")",
                    "*",
                    "[",
                    "]",
                    "%",
                    "%2",
                    "%GG",
                    "é",
                )
            for (value in invalid) {
                assertFalse(value, matchesSimpleTemplate(template, "memo://item/$value"))
            }
            assertFalse(matchesSimpleTemplate("memo://{a}2F", "memo://%2F"))
            assertFalse(matchesSimpleTemplate("memo://{a}{b}F", "memo://%2F"))
            assertTrue(matchesSimpleTemplate("memo://{a}{b}", "memo://%2F"))
            assertFalse(matchesSimpleTemplate("memo://item/%{x}", "memo://item/%20"))
            assertFalse(matchesSimpleTemplate("memo://item/%2{x}", "memo://item/%20"))
            assertTrue(matchesSimpleTemplate("memo://item/%25{x}", "memo://item/%25%20"))
            assertTrue(matchesSimpleTemplate("memo://item/{x}%25", "memo://item/%20%25"))
            assertTrue(matchesSimpleTemplate("memo://item/%25{x}", "memo://item/%25"))
        }

    @Test
    fun variableNamesStayWithinTheDeclaredSubset() =
        runBlocking {
            assertTrue(matchesSimpleTemplate("memo://item/{id_2}", "memo://item/value"))
            for (name in listOf("id.part", "id%20part", "+id", "id:2", "id*", "a,b")) {
                assertFalse(matchesSimpleTemplate("memo://item/{$name}", "memo://item/value"))
            }
        }

    @Test
    fun repeatedNamesMustHaveExactlyTheSameEncodedExpansion() =
        runBlocking {
            val template = "memo://item/{id}/{id}"
            for (value in listOf("", "a", "a%20b", "%2f")) {
                assertTrue(matchesSimpleTemplate(template, "memo://item/$value/$value"))
            }
            assertFalse(matchesSimpleTemplate(template, "memo://item/a/b"))
            assertFalse(matchesSimpleTemplate(template, "memo://item//a"))
            assertFalse(matchesSimpleTemplate(template, "memo://item/%2f/%2F"))
            assertFalse(matchesSimpleTemplate(template, "memo://item/%61/a"))
        }

    @Test
    fun adjacentUniqueAndRepeatedNamesRemainSupported() =
        runBlocking {
            assertTrue(matchesSimpleTemplate("memo://item/{a}{b}", "memo://item/"))
            assertTrue(matchesSimpleTemplate("memo://item/{a}{b}", "memo://item/ab"))
            assertTrue(matchesSimpleTemplate("memo://item/{x}{x}", "memo://item/"))
            assertTrue(matchesSimpleTemplate("memo://item/{x}{x}", "memo://item/abab"))
            assertTrue(matchesSimpleTemplate("memo://item/{x}{x}", "memo://item/%20%20"))
            assertFalse(matchesSimpleTemplate("memo://item/{x}{x}", "memo://item/ab"))
            assertFalse(matchesSimpleTemplate("memo://item/{x}{x}", "memo://item/aaa"))
            assertTrue(matchesSimpleTemplate("memo://item/{x}{y}/{x}/{y}", "memo://item/abc/ab/c"))
            assertFalse(matchesSimpleTemplate("memo://item/{x}{y}/{x}/{y}", "memo://item/abc/a/c"))
        }

    @Test(timeout = 3000)
    fun repeatedMatchingAgreesWithSmallExhaustiveExpansions() =
        runBlocking {
            for (body in listOf("{x}{x}", "{x}{y}{x}", "{x}a{y}{x}{y}")) {
                val values = listOf("", "a", "b", "aa", "ab", "ba", "bb")
                val expanded = values.flatMap { x -> values.map { y -> body.replace("{x}", x).replace("{y}", y) } }
                for (candidate in values) {
                    assertEquals(
                        "$body / $candidate",
                        candidate in expanded,
                        matchesSimpleTemplate("memo://item/$body", "memo://item/$candidate"),
                    )
                }
            }
        }

    @Test(timeout = 3000)
    fun bindingSearchSpendsTheSameRemainingDiscoveryBudget() =
        runBlocking {
            val budget = McpTemplateMatchingBudget()
            budget.consume(16777216 - 4)
            val failure = runCatching { ambiguousSearch(budget).matches() }.exceptionOrNull()
            assertEquals(McpResourceFailure.MATCH_BUDGET_EXCEEDED, (failure as? McpResourceException)?.reason)
        }

    @Test(timeout = 3000)
    fun ambiguousBindingSearchFailsWithBudgetRatherThanUnboundedBacktracking() =
        runBlocking {
            val failure = runCatching { ambiguousSearch().matches() }.exceptionOrNull()
            assertEquals(McpResourceFailure.MATCH_BUDGET_EXCEEDED, (failure as? McpResourceException)?.reason)
        }

    @Test(timeout = 3000)
    fun bindingSearchCooperatesWithCancellationAndRevocation() =
        runBlocking {
            val pending = async(start = CoroutineStart.UNDISPATCHED) { ambiguousSearch().matches() }
            pending.cancel()
            assertTrue(runCatching { pending.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            var checks = 0
            val failure =
                runCatching {
                    ambiguousSearch { check(++checks < 2) { "revoked" } }.matches()
                }.exceptionOrNull()
            assertTrue(failure is IllegalStateException)
            assertEquals(2, checks)
        }

    @Test(timeout = 3000)
    fun bindingSearchYieldsToSameLoopDeadline() =
        runBlocking {
            var checks = 0
            val failure =
                runCatching {
                    withTimeout(1) {
                        ambiguousSearch { if (++checks == 2) Thread.sleep(10) }.matches()
                    }
                }.exceptionOrNull()
            assertTrue(failure is kotlinx.coroutines.TimeoutCancellationException)
        }

    private fun ambiguousSearch(
        budget: McpTemplateMatchingBudget = McpTemplateMatchingBudget(),
        checkCurrent: () -> Unit = {},
    ): McpTemplateBindingSearch {
        val variables = (0..9).map { SimpleTemplateToken.Variable(it) }
        val tokens = variables + SimpleTemplateToken.Literal('/') + variables
        val uri = "a".repeat(32) + "/" + "a".repeat(31) + "b"
        return McpTemplateBindingSearch(SimpleTemplate(tokens, 10, true), uri, budget, checkCurrent)
    }
}
