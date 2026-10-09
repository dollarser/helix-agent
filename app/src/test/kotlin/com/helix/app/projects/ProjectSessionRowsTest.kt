package com.helix.app.projects

import com.helix.core.storage.SessionSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProjectSessionRowsTest {
    @Test fun projectionPreservesIdentityOrderAndArchivedMembers(): Unit =
        runBlocking {
            val rows = listOf(SessionSummary("new", "Same", false), SessionSummary("old", "Same", true))
            assertEquals(
                listOf(
                    ProjectSessionState.Loading,
                    ProjectSessionState.Ready(
                        listOf(ProjectSessionRow("new", "Same", false), ProjectSessionRow("old", "Same", true)),
                    ),
                ),
                projectSessionRows(flowOf(rows)).toList(),
            )
            assertEquals(
                listOf(ProjectSessionState.Loading, ProjectSessionState.Ready(emptyList())),
                projectSessionRows(flowOf(emptyList())).toList(),
            )
        }

    @Test fun failedReadIsNotEmptySuccessAndRecollectionCanRecover(): Unit =
        runBlocking {
            var failed = true
            val source =
                flow {
                    if (failed) throw IOException("fixture read failed")
                    emit(listOf(SessionSummary("one", "Ready", false)))
                }
            assertEquals(
                listOf(ProjectSessionState.Loading, ProjectSessionState.Failed),
                projectSessionRows(source).toList(),
            )
            failed = false
            assertEquals(
                listOf(
                    ProjectSessionState.Loading,
                    ProjectSessionState.Ready(listOf(ProjectSessionRow("one", "Ready", false))),
                ),
                projectSessionRows(source).toList(),
            )
        }

    @Test fun duplicateDisplaySnapshotsAreSuppressedButTitleAndArchiveChangesRemain(): Unit =
        runBlocking {
            val first = listOf(SessionSummary("one", "First", false))
            val renamed = listOf(SessionSummary("one", "Renamed", false))
            val archived = listOf(SessionSummary("one", "Renamed", true))
            val states = projectSessionRows(flowOf(first, first, renamed, archived, archived)).toList()
            assertEquals(4, states.size)
            assertEquals(ProjectSessionState.Ready(listOf(ProjectSessionRow("one", "Renamed", true))), states.last())
        }

    @Test fun cancellationAndFatalErrorsPropagateInsteadOfBecomingFailedUiState(): Unit =
        runBlocking {
            val cancellation = CancellationException("fixture cancelled")
            val fatal = AssertionError("fixture fatal")
            for (failure in listOf(cancellation, fatal)) {
                val result =
                    runCatching {
                        projectSessionRows(flow<List<SessionSummary>> { throw failure }).toList()
                    }
                val propagated = requireNotNull(result.exceptionOrNull())
                assertEquals(failure.javaClass, propagated.javaClass)
                assertEquals(failure.message, propagated.message)
                // Coroutine stacktrace recovery can copy exceptions while preserving the original as the cause.
                assertTrue(generateSequence(propagated) { it.cause }.any { it === failure })
            }
        }
}
