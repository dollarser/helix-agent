package com.helix.app.proot

import com.helix.tools.framework.ExecutionOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExecutionOwnershipStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun legacyIdentityIsPreservedButDoesNotBlockUnrelatedCalls() {
        val file = temporary.newFile("legacy")
        val owner = ExecutionOwnership.Owner("terminal", "generation")
        java.io.DataOutputStream(file.outputStream()).use {
            it.writeInt(1)
            it.writeBoolean(true)
            it.writeUTF(owner.executionId)
            it.writeUTF(owner.generation)
        }
        val host = ExecutionOwnership(ExecutionOwnershipStore(file))
        assertEquals(setOf(owner), host.retainedOwners())
        requireNotNull(host.acquire("js")).close()
        val next = ExecutionOwnership.Owner("job", "job-generation")
        requireNotNull(host.acquire("bash")).use { assertTrue(it.retain(next)) }
        assertEquals(setOf(owner, next), ExecutionOwnershipStore(file).owners())
        assertTrue(host.settle(next))
        assertEquals(setOf(owner), ExecutionOwnershipStore(file).owners())
    }

    @Test fun competingStoreUpdatesCannotLoseAnotherExecution() {
        val file = temporary.root.resolve("many")
        val first = ExecutionOwnershipStore(file)
        val second = ExecutionOwnershipStore(file)
        val a = ExecutionOwnership.Owner("a", "ga")
        val b = ExecutionOwnership.Owner("b", "gb")
        assertTrue(first.update(emptySet(), setOf(a)))
        assertFalse(second.update(emptySet(), setOf(b)))
        assertTrue(second.update(setOf(a), setOf(a, b)))
        assertFalse(first.update(setOf(a), emptySet()))
        assertEquals(setOf(a, b), ExecutionOwnershipStore(file).owners())
    }

    @Test fun durableIdentityAndClearedRecordSurviveReopening() {
        val file = temporary.root.resolve("admission/owner")
        val owner = ExecutionOwnership.Owner("execution", "generation")
        val store = ExecutionOwnershipStore(file)
        assertNull(store.read())
        assertTrue(store.compareAndSet(null, owner))
        val reopened = ExecutionOwnershipStore(file)
        assertEquals(owner, reopened.read())
        assertFalse(reopened.compareAndSet(null, owner.copy(generation = "other")))
        assertFalse(reopened.compareAndSet(owner.copy(generation = "old"), null))
        assertTrue(reopened.compareAndSet(owner, null))
        assertNull(ExecutionOwnershipStore(file).read())
    }

    @Test fun corruptRecordCannotBecomeAnEmptyAdmission() {
        val file = temporary.newFile("owner")
        file.writeText("broken")
        val store = ExecutionOwnershipStore(file)
        assertThrows(IllegalStateException::class.java) { store.read() }
        assertThrows(IllegalStateException::class.java) {
            store.compareAndSet(null, ExecutionOwnership.Owner("execution", "generation"))
        }
        assertEquals("broken", file.readText())
    }

    @Test fun rejectedOversizedReplacementPreservesPreviousIdentity() {
        val file = temporary.newFile("owner").also { it.delete() }
        val store = ExecutionOwnershipStore(file)
        val owner = ExecutionOwnership.Owner("execution", "generation")
        assertTrue(store.compareAndSet(null, owner))
        assertThrows(IllegalArgumentException::class.java) {
            store.compareAndSet(owner, owner.copy(generation = "x".repeat(129)))
        }
        assertEquals(owner, store.read())
        assertEquals(listOf("owner"), temporary.root.list()?.toList())
    }
}
