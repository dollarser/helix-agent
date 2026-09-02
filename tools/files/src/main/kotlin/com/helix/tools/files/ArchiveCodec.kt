package com.helix.tools.files

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/*
 * Restricted zip/tar codec for the `files.archive` / `files.extract` tools (roadmap HXA-047).
 *
 * This is a PURE format codec: it knows nothing about scopes, the workspace store, or the model
 * reference. Scope admission, path containment, and quota are enforced by the tool layer through
 * the core:workspace store; this file only guarantees the format itself is parsed and produced
 * fail-closed. There is deliberately no third-party archive dependency (AGENTS.md: no arbitrary
 * Maven additions) — zip is the JDK `java.util.zip`, tar is a classic-ustar reader/writer.
 *
 * Two defenses live here because they are inherent to safe parsing:
 * - ENTRY-NAME: [validateMemberName] is the primary Zip Slip / path-traversal guard. It REJECTS
 *   absolute paths and any `..` segment rather than normalizing them (the core path normalizer
 *   would silently collapse `a/../b`, which is exactly what a slip attack wants).
 * - ENTRY-TYPE: extraction is a whitelist. Only regular files and directories are accepted;
 *   symlink, character/block device, fifo, hardlink, and any PAX header fail closed. A zip
 *   "symlink" is inert here because the tool layer only ever writes regular files — it never
 *   calls createSymbolicLink.
 *
 * Bounds (entry count, per-entry size, total size, expansion ratio) are supplied by the caller
 * via [ArchiveLimits]; they are policy, not format facts, so the tool layer owns their values.
 * (Plain block comment: it documents the file, not a declaration.)
 */

/** The two restricted formats the archive tools support. */
internal enum class ArchiveFormat {
    ZIP,
    TAR,
}

/** One member the restricted codec can produce or consume. */
internal sealed interface ArchiveMember {
    /** Canonical relative name: `/`-separated, no leading `/`, no empty or `.`/`..` segment. */
    val name: String

    /** True for a directory member. */
    val isDirectory: Boolean
}

internal data class ArchiveDir(
    override val name: String,
) : ArchiveMember {
    override val isDirectory: Boolean = true
}

internal data class ArchiveFile(
    override val name: String,
    val content: ByteArray,
) : ArchiveMember {
    override val isDirectory: Boolean = false

    override fun equals(other: Any?): Boolean =
        other is ArchiveFile && other.name == name && other.content.contentEquals(content)

    override fun hashCode(): Int = 31 * name.hashCode() + content.contentHashCode()
}

/**
 * Terminal failure of the restricted codec. [reason] is a stable, sanitized code (NEVER a raw
 * path or an exception message) that the tool layer maps to a model-visible detail.
 */
internal class ArchiveCodecException(
    val reason: String,
) : RuntimeException(reason)

/** Extraction bounds supplied by the tool layer (policy, not format). */
internal data class ArchiveLimits(
    val maxEntries: Int,
    val maxEntryBytes: Long,
    val maxTotalBytes: Long,
    val maxExpansionRatio: Int,
)

@Suppress("TooManyFunctions") // one function per codec concern; splitting fragments the format layer
internal object ArchiveCodec {
    /** Builds a zip/tar container from [members] and returns its bytes. */
    fun create(
        format: ArchiveFormat,
        members: List<ArchiveMember>,
    ): ByteArray =
        when (format) {
            ArchiveFormat.ZIP -> createZip(members)
            ArchiveFormat.TAR -> createTar(members)
        }

    /**
     * Extracts [bytes], invoking [onMember] once per member in container order. Enforces the
     * entry-count, per-entry-size, total-size and expansion-ratio bounds of [limits] plus the
     * entry-type whitelist; any violation throws [ArchiveCodecException] before any further
     * member is handed out. Returns the number of members visited.
     */
    fun extract(
        format: ArchiveFormat,
        bytes: ByteArray,
        limits: ArchiveLimits,
        onMember: (ArchiveMember) -> Unit,
    ): Int =
        when (format) {
            ArchiveFormat.ZIP -> extractZip(bytes, limits, onMember)
            ArchiveFormat.TAR -> extractTar(bytes, limits, onMember)
        }

    // ── shared name defense ────────────────────────────────────────────────────────────

    /**
     * The Zip Slip / path-traversal guard. Returns the canonical name, or throws
     * [ArchiveCodecException] on an absolute path, a `.`/`..`/empty segment, a backslash, or a
     * control character. Never normalizes a `..` away.
     */
    @Suppress("ThrowsCount") // each throw is a distinct fail-closed name violation, not retryable
    fun validateMemberName(name: String): String {
        if (name.isEmpty()) throw ArchiveCodecException("BAD_NAME_EMPTY")
        if (name.startsWith("/")) throw ArchiveCodecException("BAD_NAME_ABSOLUTE")
        val out = ArrayList<String>(name.length / 8 + 1)
        for (segment in name.split("/")) {
            if (segment.isEmpty()) throw ArchiveCodecException("BAD_NAME_EMPTY_SEGMENT")
            if (segment == "." || segment == "..") throw ArchiveCodecException("BAD_NAME_DOT")
            if (segment.contains("\\")) throw ArchiveCodecException("BAD_NAME_BACKSLASH")
            for (c in segment) {
                val code = c.code
                if (code < 0x20 || code == 0x7F || code in 0x80..0x9F) {
                    throw ArchiveCodecException("BAD_NAME_CONTROL")
                }
            }
            out.add(segment)
        }
        return out.joinToString("/")
    }

    // ── zip ────────────────────────────────────────────────────────────────────────────

    private fun createZip(members: List<ArchiveMember>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            for (m in members) {
                val name = validateMemberName(m.name)
                val entry = if (m.isDirectory) ZipEntry("$name/") else ZipEntry(name)
                zos.putNextEntry(entry)
                if (m is ArchiveFile) zos.write(m.content)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun extractZip(
        bytes: ByteArray,
        limits: ArchiveLimits,
        onMember: (ArchiveMember) -> Unit,
    ): Int {
        var count = 0
        var total = 0L
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var entry = zis.getNextEntry()
            while (entry != null) {
                if (count >= limits.maxEntries) throw ArchiveCodecException("TOO_MANY_ENTRIES")
                count++
                val name = validateMemberName(entry.name.removeSuffix("/"))
                if (entry.isDirectory) {
                    onMember(ArchiveDir(name))
                } else {
                    val content = readCapped(zis, limits.maxEntryBytes)
                    total += content.size
                    checkTotal(total, bytes.size, limits)
                    onMember(ArchiveFile(name, content))
                }
                zis.closeEntry()
                entry = zis.getNextEntry()
            }
        }
        return count
    }

    /** Reads one stream entry into memory, capped at [maxBytes] (bounds memory per member). */
    private fun readCapped(
        stream: InputStream,
        maxBytes: Long,
    ): ByteArray {
        val buf = ByteArray(8192)
        val out = ByteArrayOutputStream()
        var read = 0L
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            read += n
            if (read > maxBytes) throw ArchiveCodecException("ENTRY_TOO_LARGE")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun checkTotal(
        total: Long,
        archiveBytes: Int,
        limits: ArchiveLimits,
    ) {
        if (total > limits.maxTotalBytes) throw ArchiveCodecException("TOTAL_TOO_LARGE")
        if (total > archiveBytes.toLong() * limits.maxExpansionRatio) {
            throw ArchiveCodecException("EXPANSION_EXCEEDED")
        }
    }

    // ── tar (classic ustar) ────────────────────────────────────────────────────────────

    private fun createTar(members: List<ArchiveMember>): ByteArray {
        val bos = ByteArrayOutputStream()
        for (m in members) {
            val name = validateMemberName(m.name).toByteArray(Charsets.UTF_8)
            val typeflag = if (m.isDirectory) 0x35.toByte() else 0x30.toByte()
            val content = if (m is ArchiveFile) m.content else ByteArray(0)
            bos.write(tarHeader(name, content.size.toLong(), typeflag))
            if (content.isNotEmpty()) {
                bos.write(content)
                val pad = (512 - (content.size % 512)) % 512
                if (pad > 0) bos.write(ByteArray(pad))
            }
        }
        bos.write(ByteArray(1024)) // two zero blocks terminate the archive
        return bos.toByteArray()
    }

    private fun tarHeader(
        name: ByteArray,
        size: Long,
        typeflag: Byte,
    ): ByteArray {
        val h = ByteArray(512)
        val (prefix, namePart) = splitUstarName(name)
        writeField(h, NAME_OFFSET, namePart, NAME_LEN)
        writeField(h, 100, octalBytes(420L, 8), 8) // mode 0644 (stored, not enforced)
        writeField(h, SIZE_OFFSET, octalBytes(size, SIZE_LEN), SIZE_LEN) // size
        writeField(h, MTIME_OFFSET, octalBytes(0L, MTIME_LEN), MTIME_LEN) // mtime fixed 0 (deterministic)
        h[TYPEFLAG_OFFSET] = typeflag
        // ustar magic "ustar" + NUL at MAGIC_OFFSET, version "00" at VERSION_OFFSET.
        writeField(h, MAGIC_OFFSET, "ustar".toByteArray(Charsets.US_ASCII), 5)
        h[VERSION_OFFSET] = 0x30
        h[VERSION_OFFSET + 1] = 0x30
        writeField(h, PREFIX_OFFSET, prefix, PREFIX_LEN)
        // Standard tar checksum field: 6 octal digits + NUL + space (8 bytes total).
        for (i in CHKSUM_OFFSET until CHKSUM_OFFSET + CHKSUM_LEN) h[i] = 0x20
        val sum = tarChecksum(h)
        val sumStr = sum.toString(8).padStart(6, '0')
        for (i in 0 until 6) h[CHKSUM_OFFSET + i] = sumStr[i].code.toByte()
        h[CHKSUM_OFFSET + 6] = 0
        h[CHKSUM_OFFSET + 7] = 0x20
        return h
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod", "ThrowsCount") // header scan, distinct refusals
    private fun extractTar(
        bytes: ByteArray,
        limits: ArchiveLimits,
        onMember: (ArchiveMember) -> Unit,
    ): Int {
        var pos = 0
        var count = 0
        var total = 0L
        var pendingLongName: String? = null
        while (pos + 512 <= bytes.size) {
            val header = bytes.copyOfRange(pos, pos + 512)
            if (header.all { it == 0.toByte() }) break
            if (tarChecksum(header) != parseOctal(header, CHKSUM_OFFSET, CHKSUM_LEN).toInt()) {
                throw ArchiveCodecException("BAD_TAR_CHECKSUM")
            }
            if (!isUstarMagic(header)) throw ArchiveCodecException("UNSUPPORTED_TAR")
            val size = parseOctal(header, SIZE_OFFSET, SIZE_LEN)
            if (size > limits.maxEntryBytes) throw ArchiveCodecException("ENTRY_TOO_LARGE")
            val contentStart = pos + 512
            val blockEnd = (contentStart + ((size + 511) / 512) * 512).toInt()
            if (blockEnd > bytes.size) throw ArchiveCodecException("TRUNCATED")
            val rawName =
                pendingLongName
                    ?: (
                        if (field(header, PREFIX_OFFSET, PREFIX_LEN).isNotEmpty()) {
                            field(header, PREFIX_OFFSET, PREFIX_LEN) + "/" + field(header, NAME_OFFSET, NAME_LEN)
                        } else {
                            field(header, NAME_OFFSET, NAME_LEN)
                        }
                    )
            pendingLongName = null
            when (header[TYPEFLAG_OFFSET]) {
                0x00.toByte(), 0x30.toByte() -> { // NUL or '0' — regular file
                    if (count >= limits.maxEntries) throw ArchiveCodecException("TOO_MANY_ENTRIES")
                    count++
                    val name = validateMemberName(rawName.removeSuffix("/"))
                    val content = bytes.copyOfRange(contentStart, contentStart + size.toInt())
                    total += size
                    checkTotal(total, bytes.size, limits)
                    onMember(ArchiveFile(name, content))
                }

                0x35.toByte() -> { // '5' — directory
                    if (count >= limits.maxEntries) throw ArchiveCodecException("TOO_MANY_ENTRIES")
                    count++
                    onMember(ArchiveDir(validateMemberName(rawName.removeSuffix("/"))))
                }

                0x4C.toByte() -> { // 'L' — GNU long name for the NEXT entry
                    pendingLongName =
                        bytes
                            .copyOfRange(contentStart, contentStart + size.toInt())
                            .decodeToString()
                            .trimEnd('\u0000')
                }

                0x4B.toByte() -> {
                    // 'K' — GNU long linkname: consume and ignore (linknames only matter for
                    // symlinks, which the whitelist below rejects).
                }

                0x78.toByte(), 0x67.toByte() -> {
                    throw ArchiveCodecException("UNSUPPORTED_TAR_PAX")
                }

                else -> {
                    throw ArchiveCodecException("UNSUPPORTED_TAR_ENTRY")
                }
            }
            pos = blockEnd
        }
        return count
    }

    private fun isUstarMagic(header: ByteArray): Boolean {
        for (i in 0 until 5) {
            if (header[MAGIC_OFFSET + i] != "ustar".toByteArray(Charsets.US_ASCII)[i]) return false
        }
        return true
    }

    private fun tarChecksum(h: ByteArray): Int {
        var sum = 0
        for (i in h.indices) {
            sum += if (i in CHKSUM_OFFSET until CHKSUM_OFFSET + CHKSUM_LEN) 0x20 else (h[i].toInt() and 0xFF)
        }
        return sum
    }

    private fun splitUstarName(name: ByteArray): Pair<ByteArray, ByteArray> {
        if (name.size <= NAME_LEN) return ByteArray(0) to name
        if (name.size > NAME_LEN + PREFIX_LEN) throw ArchiveCodecException("NAME_TOO_LONG_FOR_TAR")
        var i = minOf(name.size - 1, PREFIX_LEN)
        while (i >= name.size - NAME_LEN - 1) {
            if (name[i].toInt() == 0x2F && i > 0) {
                return name.copyOfRange(0, i) to name.copyOfRange(i + 1, name.size)
            }
            i--
        }
        throw ArchiveCodecException("NAME_TOO_LONG_FOR_TAR")
    }

    private fun writeField(
        h: ByteArray,
        offset: Int,
        src: ByteArray,
        len: Int,
    ) {
        if (src.size > len) throw ArchiveCodecException("NAME_TOO_LONG_FOR_TAR")
        System.arraycopy(src, 0, h, offset, src.size)
    }

    private fun octalBytes(
        value: Long,
        fieldLen: Int,
    ): ByteArray {
        if (value < 0) throw ArchiveCodecException("BAD_TAR_OCTAL")
        val max = (1L shl (3 * (fieldLen - 1))) - 1
        if (value > max) throw ArchiveCodecException("BAD_TAR_OCTAL")
        val s = value.toString(8).padStart(fieldLen - 1, '0')
        val out = ByteArray(fieldLen)
        for (i in s.indices) out[i] = s[i].code.toByte()
        out[fieldLen - 1] = 0
        return out
    }

    private fun parseOctal(
        field: ByteArray,
        offset: Int,
        len: Int,
    ): Long {
        var value = 0L
        for (i in offset until offset + len) {
            val c = field[i].toInt()
            when {
                c in 0x30..0x37 -> value = value * 8 + (c - 0x30)
                c == 0x00 || c == 0x20 -> return value
                else -> throw ArchiveCodecException("BAD_TAR_OCTAL")
            }
        }
        return value
    }

    private fun field(
        b: ByteArray,
        offset: Int,
        len: Int,
    ): String {
        var i = offset + len
        while (i > offset && b[i - 1].toInt() == 0) i--
        return b.copyOfRange(offset, i).decodeToString()
    }

    // Classic ustar header field offsets (POSIX 1003.1-1988). The restricted reader/writer only
    // touches name/mode/size/mtime/checksum/typeflag/magic/version/prefix; the remaining fields
    // stay zero. These MUST match the standard layout or real-world .tar files misparse.
    private const val NAME_OFFSET = 0
    private const val NAME_LEN = 100
    private const val SIZE_OFFSET = 124
    private const val SIZE_LEN = 12
    private const val MTIME_OFFSET = 136
    private const val MTIME_LEN = 12
    private const val CHKSUM_OFFSET = 148
    private const val CHKSUM_LEN = 8
    private const val TYPEFLAG_OFFSET = 156
    private const val MAGIC_OFFSET = 257
    private const val VERSION_OFFSET = 263
    private const val PREFIX_OFFSET = 345
    private const val PREFIX_LEN = 155
}
