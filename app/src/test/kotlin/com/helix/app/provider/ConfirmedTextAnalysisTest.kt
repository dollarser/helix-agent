package com.helix.app.provider

import com.helix.core.model.ModelEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmedTextAnalysisTest {
    @Test fun completionRetainsOnlyActualText() =
        runBlocking {
            val body =
                ConfirmedTextAnalysis.collectText(
                    flowOf(ModelEvent.TextDelta("one"), ModelEvent.TextDelta("two"), ModelEvent.Completed("stop")),
                )
            assertEquals("onetwo", body)
        }

    @Test fun incompleteTruncatedOrToolStreamsCannotBecomeProposals() =
        runBlocking {
            listOf(
                flowOf(ModelEvent.TextDelta("partial")),
                flowOf(ModelEvent.TextDelta("partial"), ModelEvent.Completed("length")),
                flowOf(ModelEvent.ToolCallFinished(0), ModelEvent.Completed("tool_calls")),
                flowOf(ModelEvent.Completed("stop")),
                flowOf(ModelEvent.TextDelta("x".repeat(8001)), ModelEvent.Completed("stop")),
                flowOf(ModelEvent.Completed("stop"), ModelEvent.TextDelta("late")),
            ).forEach { events -> assertTrue(runCatching { ConfirmedTextAnalysis.collectText(events) }.isFailure) }
        }

    @Test fun cancelledInterpretationDoesNotReturnPartialOutput() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            var returned = false
            val job =
                launch {
                    ConfirmedTextAnalysis.collectText(
                        flow {
                            emit(ModelEvent.TextDelta("partial"))
                            entered.complete(Unit)
                            awaitCancellation()
                        },
                    )
                    returned = true
                }
            entered.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled && !returned)
        }
}
