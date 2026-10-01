package com.helix.app.chat

import com.helix.app.proot.ExecutionOwnershipStore
import com.helix.tools.framework.ExecutionOwnership
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RuntimeOwnerDiskRecoveryTest {
    @Test fun reopenedNativeIdentityRequiresDeathProofWithoutBlockingUnrelatedWork() {
        val directory = Files.createTempDirectory("runtime-owner-recovery").toFile()
        val path = File(directory, "owner.bin")
        try {
            val first = ExecutionOwnership(ExecutionOwnershipStore(path))
            first.acquire("call")!!.use {
                assertThrows(IllegalStateException::class.java) {
                    NativeJavascriptOwnership(first).execute("call", "exec") { error("lost after submit") }
                }
            }
            val reopened = ExecutionOwnership(ExecutionOwnershipStore(path))
            val native = NativeJavascriptOwnership(reopened)
            val owner = requireNotNull(native.interruptedOwner())
            requireNotNull(reopened.acquire("writer")).close()
            assertFalse(native.recover(owner) { false })
            assertEquals(owner, ExecutionOwnershipStore(path).read())
            assertTrue(native.recover(owner) { true })
            assertNull(ExecutionOwnershipStore(path).read())
            reopened.acquire("writer-after-proof")!!.close()
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun truncatedDiskRecordIsNotInterpretedAsNoOriginalExecutor() {
        val directory = Files.createTempDirectory("runtime-owner-corrupt").toFile()
        val path = File(directory, "owner.bin")
        try {
            path.writeBytes(byteArrayOf(0, 1))
            val host = ExecutionOwnership(ExecutionOwnershipStore(path))
            assertThrows(IllegalStateException::class.java) { host.retainedOwners() }
            requireNotNull(host.acquire("writer")).close()
            assertEquals(2, path.length())
        } finally {
            directory.deleteRecursively()
        }
    }
}
