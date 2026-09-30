package com.helix.runtime.proot.app

import com.helix.runtime.proot.ipc.ProotJobState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ProotProcessOwnerTest {
    @Test fun missingPidCannotReleaseAStartedProcess() = verifyCleanup(false)

    @Test fun postStartSetupFailureStillOwnsProcessAndFailedGroupKillUsesFallback() = verifyCleanup(true)

    @Test fun failedLaunchDoesNotInventAProcessOrSignalAnyPid() {
        val owner = ProotProcessOwner { error("must not signal") }
        org.junit.Assert.assertThrows(java.io.IOException::class.java) {
            owner.start { throw java.io.IOException("launch rejected") }
        }
        assertFalse(owner.isAlive())
        org.junit.Assert.assertNull(owner.exitCode())
        owner.awaitExit()
    }

    private fun verifyCleanup(withPid: Boolean) {
        val destroyed = CountDownLatch(1)
        val exit = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val process =
            object : Process() {
                override fun getOutputStream() = ByteArrayOutputStream()

                override fun getInputStream() = ByteArrayInputStream(byteArrayOf())

                override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())

                override fun waitFor(): Int {
                    exit.await()
                    return 1
                }

                override fun waitFor(
                    timeout: Long,
                    unit: TimeUnit,
                ): Boolean = exit.await(timeout, unit)

                override fun exitValue(): Int {
                    check(!isAlive)
                    return 1
                }

                override fun isAlive() = exit.count > 0

                override fun destroy() {
                    destroyed.countDown()
                }

                override fun destroyForcibly(): Process {
                    destroy()
                    return this
                }
            }
        val owner = ProotProcessOwner { throw java.io.IOException("group kill failed") }
        owner.start { process }
        if (withPid) owner.pid = 42
        assertTrue(owner.isAlive())
        assertEquals(ProotJobState.ORPHANED, ProotExecutionExit.terminalState(ProotJobState.FAILED, owner.isAlive()))
        val worker =
            Thread {
                owner.awaitExit()
                finished.countDown()
            }
        worker.start()
        try {
            assertTrue(destroyed.await(2, TimeUnit.SECONDS))
            assertFalse(finished.await(30, TimeUnit.MILLISECONDS))
            exit.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(1, owner.exitCode())
        } finally {
            exit.countDown()
            worker.join(3000)
        }
    }
}
