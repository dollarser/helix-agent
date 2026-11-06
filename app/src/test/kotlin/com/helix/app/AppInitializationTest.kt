package com.helix.app

import com.helix.app.maintenance.PreviewResetRequest
import com.helix.app.maintenance.PreviewResetSection
import com.helix.app.maintenance.PreviewResetStep
import com.helix.core.storage.PreviewDatabaseResetRequired
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AppInitializationTest {
    @Test fun compatibilityCheckBlocksFactoryAndRecoveryUntilItPasses() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = AtomicInteger()
        val initialization =
            AppInitialization(
                factory = { calls.incrementAndGet() },
                preflight = {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    null
                },
            )
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(AppStartupState.Loading, initialization.state.value)
            assertEquals(0, calls.get())
        } finally {
            release.countDown()
        }
        initialization.await()
        assertEquals(1, calls.get())
    }

    @Test fun incompatiblePreflightNeverOpensOldDataOrRunsRecovery() {
        val request = resetRequest { error("No consent") }
        val initialization =
            AppInitialization<Any>(
                factory = { error("Old database must not open") },
                onReady = { error("Recovery must not start") },
                preflight = { request },
            )
        assertSame(request, runCatching { initialization.await() }.exceptionOrNull())
        assertTrue(initialization.state.value is AppStartupState.ResetRequired)
    }

    @Test fun failedReinspectionAfterDatabaseResetRequestPublishesRestoreFailure() {
        val calls = AtomicInteger()
        val failure = java.io.IOException("Synthetic reinspection failure")
        val failures = mutableListOf<Exception>()
        val initialization =
            AppInitialization<Any>(
                factory = { throw PreviewDatabaseResetRequired(reset = {}) },
                preflight = { if (calls.incrementAndGet() == 1) null else throw failure },
                onFailure = { failures += it },
                failureState = { AppStartupState.RestoreFailed },
            )
        assertSame(failure, runCatching { initialization.await() }.exceptionOrNull())
        assertEquals(AppStartupState.RestoreFailed, initialization.state.value)
        assertEquals(listOf(failure), failures)
        assertNull(initialization.readyOrNull())
    }

    private fun resetRequest(reset: (() -> Unit) -> Unit) =
        PreviewResetRequest(
            listOf(
                PreviewResetStep(PreviewResetSection.DATABASE, {}, { reset({}) }),
            ),
        )

    @Test fun cleanupRunsOnlyInsideTheExplicitlyConfirmedResetBeforeReopening() {
        val events = mutableListOf<String>()
        var old = true
        val request =
            resetRequest { beforeDelete ->
                beforeDelete()
                events += "delete"
                old = false
            }
        val initialization =
            AppInitialization(
                factory = {
                    if (old) throw request
                    events += "open"
                    Any()
                },
                beforeReset = { events += "cleanup" },
            )
        runCatching { initialization.await() }
        assertTrue(events.isEmpty())
        initialization.confirmReset(request)
        initialization.await()
        assertEquals(listOf("cleanup", "delete", "open"), events)
    }

    @Test fun openingAndReopeningNeverGrantsConsent() {
        val resets = AtomicInteger()
        val request = resetRequest { resets.incrementAndGet() }
        repeat(2) {
            val initialization = AppInitialization<Any>({ throw request })
            assertSame(request, runCatching { initialization.await() }.exceptionOrNull())
            assertTrue(initialization.state.value is AppStartupState.ResetRequired)
            assertNull(initialization.readyOrNull())
        }
        assertEquals(0, resets.get())
    }

    @Test fun explicitConfirmationRetriesOnceAndPublishesOneReadyContainer() =
        runBlocking {
            val resets = AtomicInteger()
            val attempts = AtomicInteger()
            val recovery = AtomicInteger()
            val value = Any()
            val request = resetRequest { resets.incrementAndGet() }
            val initialization =
                AppInitialization({
                    if (attempts.incrementAndGet() == 1) throw request
                    value
                }, { recovery.incrementAndGet() })
            assertSame(request, runCatching { initialization.await() }.exceptionOrNull())
            repeat(3) { initialization.confirmReset(request) }
            assertSame(value, initialization.awaitAsync())
            assertSame(value, initialization.readyOrNull())
            assertEquals(AppStartupState.Ready, initialization.state.value)
            assertEquals(1, resets.get())
            assertEquals(2, attempts.get())
            assertEquals(1, recovery.get())
        }

    @Test fun unrelatedConfirmationCannotReplaceCurrentWarning() {
        val request = resetRequest { error("must not reset") }
        val initialization = AppInitialization<Any>({ throw request })
        runCatching { initialization.await() }
        initialization.confirmReset(resetRequest { error("foreign reset") })
        assertSame(request, runCatching { initialization.await() }.exceptionOrNull())
    }

    @Test fun failedDeletionNeverStartsContainerOrRecovery() {
        val attempts = AtomicInteger()
        val failure = IllegalStateException("delete failed")
        val request = resetRequest { throw failure }
        val initialization =
            AppInitialization<Any>({
                attempts.incrementAndGet()
                throw request
            }, { error("recovery") })
        runCatching { initialization.await() }
        initialization.confirmReset(request)
        assertSame(failure, runCatching { initialization.await() }.exceptionOrNull())
        assertEquals(AppStartupState.Failed, initialization.state.value)
        assertEquals(1, attempts.get())
        assertNull(initialization.readyOrNull())
    }

    @Test fun ordinaryStartupFailureNeverOffersResetOrRetries() {
        val calls = AtomicInteger()
        val failure = IllegalArgumentException("unreadable file")
        val initialization =
            AppInitialization<Any>({
                calls.incrementAndGet()
                throw failure
            })
        repeat(2) { assertSame(failure, runCatching { initialization.await() }.exceptionOrNull()) }
        initialization.confirmReset(resetRequest { error("must not reset") })
        assertEquals(AppStartupState.Failed, initialization.state.value)
        assertEquals(1, calls.get())
    }

    @Test fun failedReinitializationDoesNotClaimReadyOrRunRecovery() {
        val attempts = AtomicInteger()
        val failure = IllegalStateException("new container failed")
        val request = resetRequest {}
        val initialization =
            AppInitialization<Any>({
                if (attempts.incrementAndGet() == 1) throw request
                throw failure
            }, { error("recovery") })
        runCatching { initialization.await() }
        initialization.confirmReset(request)
        assertSame(failure, runCatching { initialization.await() }.exceptionOrNull())
        assertEquals(AppStartupState.Failed, initialization.state.value)
        assertNull(initialization.readyOrNull())
    }
}
