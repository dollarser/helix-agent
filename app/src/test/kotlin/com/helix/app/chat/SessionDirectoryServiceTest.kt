package com.helix.app.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionDirectoryServiceTest {
    private fun withDirectory(
        search: (String, () -> Unit) -> SessionSearchUiState,
        action: (SessionDirectoryService, CoroutineScope) -> Unit,
    ) {
        Executors.newFixedThreadPool(2).asCoroutineDispatcher().use { dispatcher ->
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            try {
                val row = SessionRowUi("session", "title", 1, false, null, null)
                action(SessionDirectoryService(scope, { listOf(row) }, search), scope)
            } finally {
                scope.cancel()
            }
        }
    }

    private fun awaitScans(scope: CoroutineScope) =
        runBlocking {
            withTimeout(5000) {
                scope.coroutineContext[Job]!!
                    .children
                    .toList()
                    .forEach { it.join() }
            }
        }

    @Test fun typedQueryIsImmediateAndResultKeepsTheBoundedSearchScope() {
        val searched = mutableListOf<String>()
        withDirectory({ query, check ->
            check()
            searched.add(query)
            SessionSearchUiState(query, scannedMessages = 12, skippedMessages = 3, truncated = true)
        }) { directory, _ ->
            directory.refreshNow()
            assertEquals(
                "session",
                directory.sessions.value
                    .single()
                    .id,
            )
            directory.search("  exact input  ")
            assertEquals("  exact input  ", directory.search.value.query)
            assertTrue(
                directory.search.value.hits
                    .isEmpty(),
            )
            val result = runBlocking { withTimeout(5000) { directory.search.first { it.truncated } } }
            assertEquals(listOf("  exact input  "), searched)
            assertEquals(12, result.scannedMessages)
            assertEquals(3, result.skippedMessages)
            directory.search(" \n ")
            assertEquals(SessionSearchUiState(), directory.search.value)
        }
    }

    @Test fun cancelledNonCooperativeScanCannotOverwriteANewerResult() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        withDirectory({ query, _ ->
            if (query == "old") {
                entered.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                exited.countDown()
            }
            SessionSearchUiState(query, scannedMessages = 1)
        }) { directory, scope ->
            try {
                directory.search("old")
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                directory.search("new")
                runBlocking {
                    withTimeout(
                        5000,
                    ) { directory.search.first { it.query == "new" && it.scannedMessages == 1 } }
                }
                release.countDown()
                assertTrue(exited.await(5, TimeUnit.SECONDS))
                awaitScans(scope)
                assertEquals("new", directory.search.value.query)
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun clearCancelsAnInFlightScanWithoutDiscardingDirectoryRows() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        withDirectory({ query, check ->
            entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS))
            check()
            SessionSearchUiState(query, scannedMessages = 1)
        }) { directory, scope ->
            try {
                directory.refreshNow()
                directory.search("old")
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                directory.clearSearch()
                release.countDown()
                awaitScans(scope)
                assertEquals(SessionSearchUiState(), directory.search.value)
                assertEquals(
                    "session",
                    directory.sessions.value
                        .single()
                        .id,
                )
            } finally {
                release.countDown()
            }
        }
    }
}
