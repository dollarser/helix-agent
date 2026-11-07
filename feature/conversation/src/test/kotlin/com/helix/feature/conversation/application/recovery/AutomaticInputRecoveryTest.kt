package com.helix.feature.conversation.application.recovery

import com.helix.core.model.Clock
import com.helix.core.model.input.InputRecoveryClaim
import com.helix.core.model.input.InputRecoveryStore
import com.helix.core.model.input.RecoveryInputCandidate
import com.helix.core.model.input.SessionInputDelivery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomaticInputRecoveryTest {
    @Test fun aStaleValidationNeverFetchesOrFailsTheNewRevision() =
        runBlocking {
            val store = Store()
            val original = store.current
            AutomaticInputRecovery(store, clock, {
                store.current = it.copy(revision = it.revision + 1)
                false
            }, { "Ended" }).recover("session")
            assertEquals(original, store.failed.single())
            assertEquals(original.revision + 1, store.current.revision)
            assertTrue(store.resumed.isEmpty())
        }

    @Test fun anExistingClaimEndsWithoutAnotherRevalidation() =
        runBlocking {
            val store = Store().apply { claim = InputRecoveryClaim.AlreadyClaimed }
            AutomaticInputRecovery(store, clock, { error("must not retry") }, { "Ended" }).recover("session")
            assertEquals(listOf(store.current), store.failed)
            assertTrue(store.resumed.isEmpty())
        }

    @Test fun cancellationKeepsTheDurableClaimWithoutInventingFailure() =
        runBlocking {
            val store = Store()
            try {
                AutomaticInputRecovery(store, clock, { throw CancellationException("cancel") }, { "Ended" })
                    .recover("session")
                error("cancellation must propagate")
            } catch (_: CancellationException) {
                assertTrue(store.failed.isEmpty())
                assertTrue(store.resumed.isEmpty())
            }
        }

    private class Store : InputRecoveryStore {
        var current =
            RecoveryInputCandidate("input", "session", 2, SessionInputDelivery.QUEUE, null, "PROCESS_INTERRUPTED")
        var claim: InputRecoveryClaim? = null
        val failed = mutableListOf<RecoveryInputCandidate>()
        val resumed = mutableListOf<RecoveryInputCandidate>()

        override fun candidates(sessionId: String) = listOf(current)

        override fun claim(
            input: RecoveryInputCandidate,
            at: Long,
        ) = claim ?: InputRecoveryClaim.Claimed(input)

        override fun resume(
            input: RecoveryInputCandidate,
            at: Long,
        ): Boolean {
            resumed += input
            return input == current
        }

        override fun fail(
            input: RecoveryInputCandidate,
            reason: String,
            notice: String,
            at: Long,
        ): Boolean {
            failed += input
            return input == current
        }
    }

    private val clock =
        object : Clock {
            override fun now(): Instant = Instant.ofEpochMilli(10)
        }
}
