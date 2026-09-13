package com.helix.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 (research doc section 44 "structured test result"): [TestRunResult.parse] — the pure core
 * that recovers the aggregate result from a test/build command's raw output. Recognizes a small
 * set of common aggregate lines (Maven, Gradle, Jest, a conservative generic form); anything
 * unrecognizable is a null (no structured result), never a wrong one.
 */
class TestRunResultTest {
    @Test
    fun aMavenSummaryWithFailuresIsParsedAsFailed() {
        val r = TestRunResult.parse("Tests run: 10, Failures: 2, Errors: 1, Skipped: 1")
        assertEquals(TestRunResult.Status.FAILED, r?.status)
        assertEquals(10, r?.total)
        assertEquals(2, r?.failed)
        assertEquals(1, r?.errors)
        assertEquals(7, r?.passed)
    }

    @Test
    fun aCleanMavenSummaryIsParsedAsPassed() {
        val r = TestRunResult.parse("Tests run: 42, Failures: 0, Errors: 0, Skipped: 0")
        assertEquals(TestRunResult.Status.PASSED, r?.status)
        assertEquals(42, r?.total)
        assertEquals(42, r?.passed)
    }

    @Test
    fun aGradleSummaryWithFailuresIsParsed() {
        val r = TestRunResult.parse("> Task :app:testConsumerDebugUnitTest FAILED\n57 tests completed, 3 failed")
        assertEquals(TestRunResult.Status.FAILED, r?.status)
        assertEquals(57, r?.total)
        assertEquals(3, r?.failed)
        assertEquals(54, r?.passed)
    }

    @Test
    fun aGradleSummaryWithNoFailuresClauseIsPassed() {
        val r = TestRunResult.parse("BUILD SUCCESSFUL in 12s\n40 tests completed")
        assertEquals(TestRunResult.Status.PASSED, r?.status)
        assertEquals(40, r?.total)
        assertEquals(40, r?.passed)
        assertEquals(0, r?.failed)
    }

    @Test
    fun aJestSummaryIsParsed() {
        val r = TestRunResult.parse("Tests:       5 failed, 3 passed, 8 total")
        assertEquals(TestRunResult.Status.FAILED, r?.status)
        assertEquals(8, r?.total)
        assertEquals(3, r?.passed)
        assertEquals(5, r?.failed)
    }

    @Test
    fun aGenericTestLineIsParsed() {
        val r = TestRunResult.parse("Ran 9 tests in 0.4s\nTest run finished: 9 passed, 0 failed")
        assertEquals(TestRunResult.Status.PASSED, r?.status)
        assertEquals(9, r?.total)
        assertEquals(9, r?.passed)
    }

    @Test
    fun aPassedFailedPairWithoutTheWordTestIsNotATestRun() {
        // "X passed, Y failed" on a line with no "test" is not a recognized test aggregate.
        assertNull(TestRunResult.parse("3 checks passed, 1 failed"))
    }

    @Test
    fun aNonTestOutputYieldsNoResult() {
        assertNull(TestRunResult.parse("BUILD SUCCESSFUL in 15s\nWelcome to Gradle 8.7."))
        assertNull(TestRunResult.parse(""))
    }

    @Test
    fun theLastAggregateLineWinsAcrossModules() {
        // A multi-module build prints a per-module summary then a total; the last is the total.
        val out = "Tests run: 5, Failures: 1, Errors: 0, Skipped: 0\nTests run: 50, Failures: 0, Errors: 0, Skipped: 0"
        val r = TestRunResult.parse(out)
        assertEquals(50, r?.total)
        assertEquals(TestRunResult.Status.PASSED, r?.status)
    }

    @Test
    fun theLineIsConciseAndReflectsTheStatus() {
        val passed = TestRunResult.parse("Tests run: 10, Failures: 0, Errors: 0")
        val failed = TestRunResult.parse("10 tests completed, 2 failed")
        assertTrue(passed?.line.orEmpty().startsWith("Test run: PASSED"))
        assertTrue(failed?.line.orEmpty().startsWith("Test run: FAILED"))
    }
}
