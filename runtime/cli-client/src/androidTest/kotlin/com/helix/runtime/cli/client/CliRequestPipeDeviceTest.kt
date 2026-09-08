package com.helix.runtime.cli.client

import android.os.Binder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class CliRequestPipeDeviceTest {
    @Test
    fun consumedUploadRetainsAcceptedReply() {
        val payload = ByteArray(256 * 1024) { (it % 251).toByte() }
        val hash = cliPayloadSha256(payload)
        val binder =
            object : Binder() {
                override fun onTransact(
                    code: Int,
                    data: Parcel,
                    reply: Parcel?,
                    flags: Int,
                ): Boolean {
                    data.enforceInterface(CliRuntimeProtocol.DESCRIPTOR)
                    val jobId = requireNotNull(data.readString())
                    assertEquals(hash, data.readString())
                    val input =
                        requireNotNull(
                            data.readParcelable<ParcelFileDescriptor>(
                                ParcelFileDescriptor::class.java.classLoader,
                            ),
                        )
                    assertArrayEquals(payload, CliPfdChannel.read(input, CliModelRequestCodec.MAX_BYTES))
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
        val result =
            CliModelJobWire.transact(
                binder,
                CliRuntimeProtocol.TRANSACTION_JOB_SUBMIT,
                "job_000000000003",
                hash,
                payload,
            )
        assertEquals(CliRuntimeProtocol.REPLY_JOB_ACCEPTED, result.status)
        assertNull(result.cause)
    }

    @Test
    fun rejectedSubmissionDoesNotLeaveUncaughtWriterFailure() {
        val name = "cli-request-job_000000000002"
        val uncaught = AtomicReference<Throwable?>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            if (thread.name == name) uncaught.set(failure) else previous?.uncaughtException(thread, failure)
        }
        try {
            var calls = 0
            val binder =
                object : Binder() {
                    override fun onTransact(
                        code: Int,
                        data: Parcel,
                        reply: Parcel?,
                        flags: Int,
                    ): Boolean {
                        calls++
                        return false
                    }
                }
            val result =
                CliModelJobWire.transact(
                    binder,
                    CliRuntimeProtocol.TRANSACTION_JOB_SUBMIT,
                    "job_000000000002",
                    "a".repeat(64),
                    ByteArray(256 * 1024),
                )
            val deadline = System.nanoTime() + 3_000_000_000L
            while (Thread.getAllStackTraces().keys.any { it.name == name } && System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue(Thread.getAllStackTraces().keys.none { it.name == name })
            assertNull("request writer exception escaped: ${uncaught.get()}", uncaught.get())
            assertEquals(CliRuntimeProtocol.REPLY_JOB_INVALID, result.status)
            assertEquals(1, calls)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
