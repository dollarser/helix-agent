package com.helix.runtime.proot.core

import java.io.File
import java.io.RandomAccessFile

/**
 * Minimal, read-only scan of a ZIP central directory (HXA-084). The JDK's
 * [java.util.zip.ZipEntry] API does not expose the entry's external attributes,
 * and those carry the unix mode bits — the only place a hostile archive can
 * request a symlink/hardlink entry. This scanner reads the EOCD + central
 * directory records directly (the format is fixed by the ZIP spec) and rejects
 * any entry whose unix file type is neither "no unix info" (0) nor regular
 * file (0x8000).
 *
 * Only metadata is read (names + external attributes); no entry content is
 * touched. Any structural anomaly (missing EOCD, truncated record, oversized
 * name) is a [JobArchiveException] — fail-closed, like the rest of the
 * archive handling.
 */
internal object ZipCentralDirectoryScan {
    private const val EOCD_SIGNATURE = 0x06054b50

    // The CD record signature stored as RandomAccessFile.readInt() returns it:
    // the ZIP spec stores signatures little-endian (bytes 50 4B 01 02) but
    // readInt assembles big-endian, so the assembled constant is byte-reversed.
    private const val CD_SIGNATURE = 0x504b0102

    /** EOCD is 22 bytes + a variable comment of at most 65535 bytes. */
    private const val EOCD_MAX_SCAN_BYTES = 22 + 65535

    /** Offset of the 4-byte external attributes field inside a CD record. */
    private const val CD_EXTERNAL_ATTRIBUTES_OFFSET = 38

    // One throw per distinct structural violation of the central directory.
    @Suppress("ThrowsCount")
    fun rejectNonRegularEntries(archive: File) {
        RandomAccessFile(archive, "r").use { raf ->
            val size = raf.length()
            val eocdOffset =
                findEocd(raf, size)
                    ?: throw JobArchiveException("archive is not a ZIP (no EOCD)")
            if (eocdOffset + 22 > size) throw JobArchiveException("archive EOCD is truncated")
            // EOCD layout: signature@0, disk@4, cdDisk@6, diskEntries@8,
            // totalEntries@10, cdSize@12, cdOffset@16, commentLen@20.
            raf.seek(eocdOffset + 10)
            val count = readUnsignedShort(raf)
            raf.seek(eocdOffset + 16)
            val cdOffset = readUnsignedInt(raf)
            if (cdOffset < 0 || cdOffset >= size) {
                throw JobArchiveException("archive central directory offset is out of bounds")
            }
            raf.seek(cdOffset)
            var offset = cdOffset
            repeat(count) {
                raf.seek(offset)
                val header = raf.readInt().toLong()
                if (header != CD_SIGNATURE.toLong()) {
                    throw JobArchiveException("archive central directory record is malformed")
                }
                // The length fields sit at record offset 28.
                raf.seek(offset + 28)
                val nameLength = readUnsignedShort(raf).toInt()
                val extraLength = readUnsignedShort(raf).toInt()
                val commentLength = readUnsignedShort(raf).toInt()
                if (nameLength < 0 || extraLength < 0 || commentLength < 0) {
                    throw JobArchiveException("archive central directory lengths are malformed")
                }
                // External attributes at record offset 38; the unix file type is
                // the top 4 bits of their high 16 bits.
                raf.seek(offset + CD_EXTERNAL_ATTRIBUTES_OFFSET)
                val externalAttributes = readUnsignedInt(raf)
                val unixMode = (externalAttributes ushr 16) and 0xFFFFL
                val type = unixMode and 0xF000L
                if (type != 0L && type != 0x8000L) {
                    throw JobArchiveException("archive contains a non-regular entry")
                }
                // Skip the variable part: name + extra + comment.
                offset += 46 + nameLength + extraLength + commentLength
                if (offset > size) {
                    throw JobArchiveException("archive central directory record is truncated")
                }
            }
        }
    }

    @Suppress("ReturnCount") // one return per scan outcome (found / not found)
    private fun findEocd(
        raf: RandomAccessFile,
        size: Long,
    ): Long? {
        if (size < 22) return null
        val scanFrom = (size - EOCD_MAX_SCAN_BYTES).coerceAtLeast(0)
        val window = ByteArray((size - scanFrom).toInt())
        raf.seek(scanFrom)
        raf.readFully(window)
        // Search from the END: a comment can contain the signature bytes.
        for (i in window.size - 22 downTo 0) {
            if (windowHasSignatureAt(window, i)) return scanFrom + i
        }
        return null
    }

    private fun windowHasSignatureAt(
        window: ByteArray,
        i: Int,
    ): Boolean =
        window[i].toInt() == (EOCD_SIGNATURE and 0xFF) &&
            window[i + 1].toInt() == ((EOCD_SIGNATURE ushr 8) and 0xFF) &&
            window[i + 2].toInt() == ((EOCD_SIGNATURE ushr 16) and 0xFF) &&
            window[i + 3].toInt() == ((EOCD_SIGNATURE ushr 24) and 0xFF)

    private fun readUnsignedShort(raf: RandomAccessFile): Int {
        val low = raf.read()
        val high = raf.read()
        if (low < 0 || high < 0) throw JobArchiveException("archive is truncated")
        return low or (high shl 8)
    }

    /** Little-endian unsigned int (the ZIP format is LE). */
    private fun readUnsignedByte(raf: RandomAccessFile): Long {
        val b = raf.read().toLong()
        if (b < 0) throw JobArchiveException("archive is truncated")
        return b
    }

    private fun readUnsignedInt(raf: RandomAccessFile): Long =
        readUnsignedByte(raf) or
            (readUnsignedByte(raf) shl 8) or
            (readUnsignedByte(raf) shl 16) or
            (readUnsignedByte(raf) shl 24)
}
