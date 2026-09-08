package com.helix.runtime.cli.client

import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CliPrematureAcceptanceDeviceTest {
    @Test
    fun acceptedReplyWithoutReadingPayloadBecomesUnavailable() = verifyPrematureReply(false)

    @Test
    fun retainedUnreadPipeCannotMakeCleanupWaitIndefinitely() = verifyPrematureReply(true)

    private fun verifyPrematureReply(retainReader: Boolean) {
        val held = AtomicReference<ParcelFileDescriptor?>()
        val release = Executors.newSingleThreadScheduledExecutor()
        val watchdog = release.schedule({ held.getAndSet(null)?.close() }, 5, TimeUnit.SECONDS)
        val jobId = "job_000000000004"
        var calls = 0
        try {
            val binder =
                object : Binder() {
                    override fun onTransact(
                        code: Int,
                        data: Parcel,
                        reply: Parcel?,
                        flags: Int,
                    ): Boolean {
                        calls++
                        data.enforceInterface(CliRuntimeProtocol.DESCRIPTOR)
                        assertEquals(jobId, data.readString())
                        val hash = requireNotNull(data.readString())
                        if (retainReader) held.set(data.readParcelable(ParcelFileDescriptor::class.java.classLoader))
                        requireNotNull(reply).writeInt(CliRuntimeProtocol.REPLY_JOB_ACCEPTED)
                        reply.writeString(
                            CliModelJobRecordCodec.encode(
                                CliModelJobRecord(jobId, hash, CliModelJobState.PENDING, 1L),
                            ),
                        )
                        reply.writeInt(0)
                        return true
                    }
                }
            val start = System.nanoTime()
            val result =
                CliModelJobWire.transact(
                    binder,
                    CliRuntimeProtocol.TRANSACTION_JOB_SUBMIT,
                    jobId,
                    "a".repeat(64),
                    ByteArray(256 * 1024),
                )
            assertTrue("cleanup waited for watchdog", System.nanoTime() - start < 3_000_000_000L)
            assertEquals(CliRuntimeProtocol.REPLY_JOB_INVALID, result.status)
            assertEquals(CliRuntimeVerification.Cause.HANDSHAKE_FAILED, result.cause)
            assertEquals(null, result.record)
            assertEquals(1, calls)
        } finally {
            held.getAndSet(null)?.close()
            watchdog.cancel(false)
            release.shutdownNow()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (writerAlive(jobId) && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue("writer survived after reader was released", !writerAlive(jobId))
        }
    }

    private fun writerAlive(jobId: String) = Thread.getAllStackTraces().keys.any { it.name == "cli-request-$jobId" }
}
