package com.helix.app.chat

import com.helix.tools.framework.ExecutionOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeJavascriptOwnershipTest {
    private class Store : ExecutionOwnership.Store {
        var value: ExecutionOwnership.Owner? = null

        override fun read() = value

        override fun compareAndSet(
            expected: ExecutionOwnership.Owner?,
            replacement: ExecutionOwnership.Owner?,
        ): Boolean {
            if (value != expected) return false
            value = replacement
            return true
        }
    }

    @Test fun hostRestartCannotAdmitWriterBeforeNativeDeathProof() {
        val store = Store()
        val old = ExecutionOwnership(store)
        old.acquire("launch")!!.use {
            assertThrows(IllegalStateException::class.java) {
                NativeJavascriptOwnership(old).execute("launch", "execution") { error("host stopped after submit") }
            }
        }
        val restarted = ExecutionOwnership(store)
        val recovery = NativeJavascriptOwnership(restarted)
        val original = requireNotNull(recovery.interruptedOwner())
        assertNull(restarted.acquire("writer"))
        assertFalse(recovery.recover(original) { false })
        assertNull(restarted.acquire("writer-after-refusal"))
        assertThrows(IllegalStateException::class.java) { recovery.recover(original) { error("binder unavailable") } }
        assertEquals(original, restarted.retainedOwner())
        assertTrue(
            recovery.recover(original) { id ->
                assertEquals("execution", id)
                true
            },
        )
        assertNotNull(restarted.acquire("writer-after-proof")?.also { it.close() })
        assertFalse(recovery.recover(original) { error("stale recovery must not retire another process") })
    }

    @Test fun liveLauncherCannotBeReconciledAndSuccessKeepsItsEffectWindow() {
        val store = Store()
        val host = ExecutionOwnership(store)
        val native = NativeJavascriptOwnership(host)
        host.acquire("launch")!!.use {
            assertEquals(
                42,
                native.execute("launch", "execution") {
                    val owner = requireNotNull(native.interruptedOwner())
                    assertFalse(native.recover(owner) { error("still launching") })
                    assertNull(host.acquire("writer"))
                    42
                },
            )
            assertNull(host.retainedOwner())
            assertNull(host.acquire("writer-before-caller-return"))
        }
        assertNotNull(host.acquire("writer")?.also { it.close() })
    }
}
