package com.helix.core.agent

import com.helix.core.model.ArtifactRef
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletionReportTest {
    @Test
    fun aCompleteReportWithNoRemainingIsValid() {
        val report =
            CompletionReport(
                CompletionStatus.COMPLETE,
                "login fixed",
                listOf(ArtifactRef("fix.patch")),
            )
        assertTrue(report.isComplete)
    }

    @Test
    fun aCompleteReportCannotListRemaining() {
        assertThrows(IllegalArgumentException::class.java) {
            CompletionReport(CompletionStatus.COMPLETE, "done", remaining = listOf("leftover"))
        }
    }

    @Test
    fun aBlockedReportMustNameTheBlocker() {
        CompletionReport(CompletionStatus.BLOCKED, "stuck", remaining = listOf("waiting for the device"))
        assertThrows(IllegalArgumentException::class.java) {
            CompletionReport(CompletionStatus.BLOCKED, "stuck")
        }
    }

    @Test
    fun aPartialReportMayListRemaining() {
        CompletionReport(CompletionStatus.PARTIAL, "halfway", remaining = listOf("instrumentation tests"))
    }

    @Test
    fun aBlankSummaryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            CompletionReport(CompletionStatus.COMPLETE, "   ")
        }
    }

    @Test
    fun anOversizedListIsRejected() {
        val tooMany = List(CompletionReport.MAX_LIST + 1) { "v$it" }
        assertThrows(IllegalArgumentException::class.java) {
            CompletionReport(CompletionStatus.COMPLETE, "done", verification = tooMany)
        }
    }
}
