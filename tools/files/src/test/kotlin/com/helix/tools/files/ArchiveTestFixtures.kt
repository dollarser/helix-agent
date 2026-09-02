package com.helix.tools.files

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * HXA-047 raw-archive fixture builders shared by [ArchiveCodecTest] and [ArchiveToolsTest].
 *
 * They exist so the security tests can feed the restricted codec *malformed* containers the codec's
 * own `create` would never produce (Zip Slip entry names, symlink/PAX tar entries, corrupt
 * checksums, zip bombs) — the point of the matrix's "Zip Slip/膨胀比 fixture". No dependency is
 * involved: zip is the JDK `java.util.zip`, the tar block is a hand-built classic-ustar header.
 */
internal object ArchiveTestFixtures {
    /** A zip holding a single entry with an arbitrary (possibly malicious) [name]. */
    fun zipWithEntry(
        name: String,
        content: ByteArray,
    ): ByteArray = zipWithMembers(listOf(name to content))

    /** A zip holding the given [entries] in order, each written with its raw [name]. */
    fun zipWithMembers(entries: List<Pair<String, ByteArray>>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /**
     * A single classic-ustar block (512-byte header + padded content) with an explicit [typeflag]
     * and an optional [checksumDelta] to corrupt the checksum. Mirrors the codec's fixed ustar
     * offsets so a well-formed block parses, and a bad typeflag/checksum is reached cleanly.
     */
    fun tarBlock(
        name: String,
        typeflag: Byte,
        content: ByteArray,
        checksumDelta: Int = 0,
    ): ByteArray {
        val h = ByteArray(512)
        name.toByteArray().copyInto(h, 0)
        writeOctal(h, 124, 12, content.size.toLong())
        writeOctal(h, 136, 12, 0L)
        h[156] = typeflag
        "ustar".toByteArray().copyInto(h, 257)
        h[263] = 0x30
        h[264] = 0x30
        for (i in 148 until 156) h[i] = 0x20
        var sum = 0
        for (b in h) sum += (b.toInt() and 0xFF)
        val cs = (sum + checksumDelta).toString(8).padStart(6, '0')
        for (i in 0 until 6) h[148 + i] = cs[i].code.toByte()
        h[154] = 0
        h[155] = 0x20
        val pad = (512 - (content.size % 512)) % 512
        return h + content + ByteArray(pad)
    }

    private fun writeOctal(
        h: ByteArray,
        offset: Int,
        len: Int,
        value: Long,
    ) {
        val s = value.toString(8).padStart(len - 1, '0')
        for (i in s.indices) h[offset + i] = s[i].code.toByte()
        h[offset + len - 1] = 0
    }
}
