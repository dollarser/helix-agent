package com.helix.app.terminal

import com.helix.tools.framework.ExecutionOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalStartTransactionTest {
    private class Store : ExecutionOwnership.Store {
        var owner: Set<ExecutionOwnership.Owner> = emptySet()
        var failAfterWrite = false

        override fun owners() = owner

        override fun update(
            expected: Set<ExecutionOwnership.Owner>,
            replacement: Set<ExecutionOwnership.Owner>,
        ): Boolean {
            if (owner != expected) return false
            owner = replacement
            if (failAfterWrite) {
                failAfterWrite = false
                error("write committed but response failed")
            }
            return true
        }
    }

    private val retained = Store()
    private val binding = Store()
    private val ownership = ExecutionOwnership(retained)
    private val owner = ExecutionOwnership.Owner("terminal", "generation")
    private val transaction = TerminalStartTransaction(ownership, binding)

    @Test fun oldStrandedIdentityRemainsRecoverableButDoesNotBlockJsOrBash() {
        assertThrows(IllegalStateException::class.java) {
            requireNotNull(ownership.acquire("legacy-launch")).use { permit ->
                check(binding.compareAndSet(null, owner))
                check(permit.retain(owner))
                error("connect failed before START, as in v0.0.4")
            }
        }
        assertEquals(owner, ownership.retainedOwners().singleOrNull())
        requireNotNull(ownership.acquire("js")).close()
        requireNotNull(ownership.acquire("bash")).close()
    }

    @Test fun uncertainStorageCommitBeforeAnySubmissionIsSafelyRecovered() {
        retained.failAfterWrite = true
        assertThrows(IllegalStateException::class.java) {
            transaction.launch<String>("launch", owner, { error("must not submit") }, { false })
        }
        assertNull(retained.read())
        assertNull(binding.read())
        assertNotNull(ownership.acquire("js")?.also { it.close() })
    }

    @Test fun diagnosticsExplainCapacityWithoutDemandingUnrelatedSettlement() {
        requireNotNull(ownership.acquire("live")).use { permit ->
            assertTrue(ownership.busyFailure().toString().contains("capacity"))
            permit.retain(owner)
        }
        assertTrue(ownership.busyFailure().toString().contains("unrelated work may continue"))
        assertEquals(owner, ownership.retainedOwners().singleOrNull())
    }

    @Test fun connectionFailureBeforeStartReleasesReservationAndAllowsJsAndBash() {
        assertThrows(IllegalStateException::class.java) {
            transaction.launch<String>("launch", owner, { error("connect failed before START") }, { false })
        }
        assertNull(binding.read())
        assertNull(retained.read())
        assertNotNull(ownership.acquire("js")?.also { it.close() })
        assertNotNull(ownership.acquire("bash")?.also { it.close() })
    }

    @Test fun uncertainStartKeepsBothBindingsAcrossRestart() {
        assertThrows(IllegalStateException::class.java) {
            transaction.launch<String>("launch", owner, { mark ->
                mark()
                error("reply lost")
            }, { false })
        }
        assertEquals(owner, retained.read())
        assertEquals(owner, binding.read())
        requireNotNull(ExecutionOwnership(retained).acquire("js")).close()
    }

    @Test fun successfulStartKeepsIdentityButAllowsBashBeforeExactSettlement() {
        assertEquals(
            "accepted",
            transaction.launch("launch", owner, { mark ->
                mark()
                "accepted"
            }, { false }),
        )
        requireNotNull(ownership.acquire("bash")).close()
        assertFalse(ownership.settle(owner.copy(generation = "other")))
        assertTrue(ownership.settle(owner))
        assertNotNull(ownership.acquire("bash")?.also { it.close() })
    }

    @Test fun definitiveRefusalReleasesReservation() {
        assertThrows(IllegalStateException::class.java) {
            transaction.launch("launch", owner, { mark ->
                mark()
                "START_REFUSED"
            }, { true })
        }
        assertNull(retained.read())
        assertNull(binding.read())
    }

    @Test fun failedSecondaryStartDoesNotReleaseThePrimary() {
        retained.owner = setOf(owner.copy(executionId = "primary"))
        assertThrows(IllegalStateException::class.java) {
            transaction.launch<String>("secondary", owner, { error("connect") }, { false })
        }
        assertNull(binding.read())
        assertEquals("primary", retained.read()?.executionId)
    }

    @Test fun conflictingOwnerCannotBeOverwrittenOrCleared() {
        retained.owner = setOf(owner.copy(generation = "other"))
        assertThrows(IllegalStateException::class.java) {
            transaction.launch("launch", owner, { error("must not submit") }, { false })
        }
        assertNull(binding.read())
        assertEquals("other", retained.read()?.generation)
    }
}
