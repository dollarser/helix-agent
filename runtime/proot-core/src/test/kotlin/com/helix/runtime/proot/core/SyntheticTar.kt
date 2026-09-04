package com.helix.runtime.proot.core

import java.io.ByteArrayOutputStream

/**
 * Minimal in-memory GNU tar builder for tests: regular files, directories and
 * symlinks, with correct header checksums, block padding and the two zero-block
 * end-of-archive marker. Mirrors the format `scripts/deterministic_tar.py` emits.
 */
object SyntheticTar {
    fun archive(vararg entries: Entry): ByteArray {
        val out = ByteArrayOutputStream()
        for (entry in entries) out.write(entry.bytes())
        out.write(ByteArray(1024)) // two zero blocks
        return out.toByteArray()
    }

    data class Entry(
        val name: String,
        /** Payload as `String` or `ByteArray`; empty for directories and symlinks. */
        val content: Any = ByteArray(0),
        val type: Char = '0', // '0' file, '5' dir, '2' symlink
        val mode: Int = 0x1A4,
        val linkTarget: String = "",
    ) {
        private fun payload(): ByteArray =
            when (content) {
                is String -> content.encodeToByteArray()
                is ByteArray -> content
                else -> throw IllegalArgumentException("test content must be String or ByteArray")
            }

        fun bytes(): ByteArray {
            val payload = payload()
            val header = ByteArray(512)
            val nameBytes = name.encodeToByteArray()
            require(nameBytes.size <= 99) { "test name too long" }
            nameBytes.copyInto(header)
            octal(mode, 7).copyInto(header, 100)
            octal(0, 7).copyInto(header, 108) // uid
            octal(0, 7).copyInto(header, 116) // gid
            octal(payload.size, 11).copyInto(header, 124)
            octal(0, 11).copyInto(header, 136) // mtime
            for (i in 148..155) header[i] = ' '.code.toByte()
            header[156] = type.code.toByte()
            val linkBytes = linkTarget.encodeToByteArray()
            require(linkBytes.size <= 99) { "test link target too long" }
            linkBytes.copyInto(header, 157)
            "ustar\u0000".encodeToByteArray().copyInto(header, 257)
            "00".encodeToByteArray().copyInto(header, 263)
            val sum = (0 until 512).sumOf { header[it].toInt() and 0xFF }
            val checksum = Integer.toOctalString(sum).padStart(6, '0').encodeToByteArray()
            checksum.copyInto(header, 148)
            header[154] = 0
            header[155] = ' '.code.toByte()
            val pad = ByteArray((512 - (payload.size % 512)) % 512)
            return header + payload + pad
        }
    }

    /** A member of a forbidden type (hard link '1', char device '3', block '4', fifo '6'). */
    fun forbidden(
        name: String,
        type: Char,
    ): Entry = Entry(name, type = type)

    /** GNU tar field layout: (width-2) octal digits, then NUL, then space. */
    private fun octal(
        value: Int,
        width: Int,
    ): ByteArray {
        val s = Integer.toOctalString(value).padStart(width - 2, '0').encodeToByteArray()
        val out = ByteArray(width)
        s.copyInto(out, 0, 0, s.size.coerceAtMost(width - 2))
        out[width - 2] = 0
        out[width - 1] = ' '.code.toByte()
        return out
    }
}
