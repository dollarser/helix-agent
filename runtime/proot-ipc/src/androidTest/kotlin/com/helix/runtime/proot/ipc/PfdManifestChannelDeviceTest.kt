package com.helix.runtime.proot.ipc

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PfdManifestChannelDeviceTest {
    @Test
    fun successfulReadAndWriteCloseBothOwnedEnds() {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            val bytes = "manifest".toByteArray()
            PfdManifestChannel.writeToEnd(pipe[1], bytes)
            assertFalse(pipe[1].fileDescriptor.valid())
            assertArrayEquals(bytes, PfdManifestChannel.readFromStart(pipe[0]))
            assertFalse(pipe[0].fileDescriptor.valid())
        } finally {
            pipe.forEach { it.close() }
        }
    }

    @Test
    fun emptyReadAlsoClosesItsEnd() {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            pipe[1].close()
            assertArrayEquals(byteArrayOf(), PfdManifestChannel.readFromStart(pipe[0]))
            assertFalse(pipe[0].fileDescriptor.valid())
        } finally {
            pipe.forEach { it.close() }
        }
    }

    @Test
    fun rejectedWriteClosesItsEndAndSignalsEof() {
        val pipe = ParcelFileDescriptor.createPipe()
        try {
            assertThrows(ProotIpcException::class.java) {
                PfdManifestChannel.writeToEnd(pipe[1], ByteArray(ProotRuntimeProtocol.MAX_MANIFEST_BYTES.toInt() + 1))
            }
            assertFalse(pipe[1].fileDescriptor.valid())
            assertArrayEquals(byteArrayOf(), PfdManifestChannel.readFromStart(pipe[0]))
        } finally {
            pipe.forEach { it.close() }
        }
    }

    @Test
    fun oversizedIncomingStreamClosesReadEnd() {
        val pipe = ParcelFileDescriptor.createPipe()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val writer =
                executor.submit {
                    try {
                        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {
                            it.write(ByteArray(ProotRuntimeProtocol.MAX_MANIFEST_BYTES.toInt() + 1))
                        }
                    } catch (_: IOException) {
                        // The receiver is allowed to close the oversized pipe before its writer finishes.
                    }
                }
            assertThrows(ProotIpcException::class.java) { PfdManifestChannel.readFromStart(pipe[0]) }
            assertFalse(pipe[0].fileDescriptor.valid())
            writer.get(5, TimeUnit.SECONDS)
        } finally {
            pipe.forEach { it.close() }
            executor.shutdownNow()
        }
    }

    @Test
    fun invalidReadReportsProtocolFailure() {
        val pipe = ParcelFileDescriptor.createPipe()
        pipe.forEach { it.close() }
        assertThrows(ProotIpcException::class.java) { PfdManifestChannel.readFromStart(pipe[0]) }
        assertFalse(pipe[0].fileDescriptor.valid())
    }
}
