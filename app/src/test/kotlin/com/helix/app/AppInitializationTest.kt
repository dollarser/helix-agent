package com.helix.app

import com.helix.core.storage.PreviewDatabaseResetRequired
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class AppInitializationTest {
    @Test fun cleanupRunsOnlyInsideTheExplicitlyConfirmedResetBeforeReopening() {
        val events = mutableListOf<String>()
        var old = true
        val request =
            PreviewDatabaseResetRequired { beforeDelete ->
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
        val request = PreviewDatabaseResetRequired { resets.incrementAndGet() }
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
            val request = PreviewDatabaseResetRequired { resets.incrementAndGet() }
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
        val request = PreviewDatabaseResetRequired { error("must not reset") }
        val initialization = AppInitialization<Any>({ throw request })
        runCatching { initialization.await() }
        initialization.confirmReset(PreviewDatabaseResetRequired { error("foreign reset") })
        assertSame(request, runCatching { initialization.await() }.exceptionOrNull())
    }

    @Test fun failedDeletionNeverStartsContainerOrRecovery() {
        val attempts = AtomicInteger()
        val failure = IllegalStateException("delete failed")
        val request = PreviewDatabaseResetRequired { throw failure }
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
        initialization.confirmReset(PreviewDatabaseResetRequired { error("must not reset") })
        assertEquals(AppStartupState.Failed, initialization.state.value)
        assertEquals(1, calls.get())
    }

    @Test fun failedReinitializationDoesNotClaimReadyOrRunRecovery() {
        val attempts = AtomicInteger()
        val failure = IllegalStateException("new container failed")
        val request = PreviewDatabaseResetRequired {}
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
