package com.helix.runtime.proot.ipc

import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Bounded manifest transfer over a pipe [ParcelFileDescriptor] (HXA-083 "PFD
 * manifest"; the job input/output manifests of section 6.6 reuse this channel in
 * HXA-084).
 *
 * Cross-UID rule: files cannot cross app-private storage, so the server creates a
 * pipe, writes the manifest into the kernel pipe buffer (hard-capped at
 * [ProotRuntimeProtocol.MAX_MANIFEST_BYTES]), closes its write end, and parcels the
 * read end. The client reads exactly up to the cap and treats both an oversized
 * stream and a truncated one as protocol failures — there is no "partial manifest".
 */
object PfdManifestChannel {
    private const val CHUNK = 16 * 1024

    /**
     * Writes [bytes] into the pipe's WRITE end and closes that end. The caller owns
     * (and must parcel or close) the read end. Throws when [bytes] exceeds the cap.
     */
    fun writeToEnd(
        writeEnd: ParcelFileDescriptor,
        bytes: ByteArray,
    ) {
        try {
            requireWithinCap(bytes.size)
            FileOutputStream(writeEnd.fileDescriptor).use { it.write(bytes) }
        } catch (e: IOException) {
            writeEnd.close()
            throw ProotIpcException("manifest write failed: ${e.message?.take(80)}", e)
        } catch (e: ProotIpcException) {
            writeEnd.close()
            throw e
        }
        writeEnd.close()
    }

    /** Throws when [size] exceeds the hard manifest cap (both directions share it). */
    private fun requireWithinCap(size: Int) {
        if (size.toLong() > ProotRuntimeProtocol.MAX_MANIFEST_BYTES) {
            throw ProotIpcException("manifest exceeds ${ProotRuntimeProtocol.MAX_MANIFEST_BYTES} bytes")
        }
    }

    /**
     * Reads one complete manifest from the pipe's READ end and closes it.
     * Throws on over-cap or any I/O error; returns an empty array for a clean
     * zero-length manifest (legal but meaningless at handshake).
     */
    fun readFromStart(readEnd: ParcelFileDescriptor): ByteArray {
        val out = ByteArrayOutputStream()
        try {
            readAll(ParcelFileDescriptor.AutoCloseInputStream(readEnd), out)
        } catch (e: IOException) {
            throw ProotIpcException("manifest read failed: ${e.message?.take(80)}", e)
        }
        return out.toByteArray()
    }

    private fun readAll(
        input: java.io.InputStream,
        out: ByteArrayOutputStream,
    ) {
        val buf = ByteArray(CHUNK)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            requireWithinCap(out.size())
        }
    }
}
