package com.helix.runtime.proot.core

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption

/** Failure reading or validating a GNU tar stream (malformed, truncated, unsafe). */
class TarStreamException(
    message: String,
) : IllegalArgumentException(message)

/** The only member kinds a RootFS archive may contain (everything else is rejected). */
enum class TarMemberType {
    FILE,
    DIR,
    SYMLINK,
}

/** One validated archive member (path already safe, symlink target already validated). */
data class TarMember(
    val path: String,
    val type: TarMemberType,
    val size: Long,
    val mode: Int,
    val linkTarget: String?,
)

/** Result of extracting one archive: the member list (input order) and payload bytes written. */
data class TarExtraction(
    val members: List<TarMember>,
    val extractedBytes: Long,
)

/**
 * Strict read-only GNU tar reader for the fixed RootFS archive (HXA-082). Accepts
 * exactly the member kinds a RootFS needs — regular files, directories, symlinks —
 * plus GNU long-name (`L`) / long-linkname (`K`) headers. Hard links, devices, FIFOs
 * and every other typeflag are REJECTED (fail-closed): the archive is hash-locked by
 * the runtime lock, so a surprise type is corruption, not a feature.
 *
 * Every member path and symlink target is validated by [TarPathSafety] BEFORE
 * anything is written. The reader never follows symlinks and never resolves a
 * target against the host filesystem. The caller owns the destination tree: a
 * mid-stream violation aborts with [TarStreamException] and the caller must delete
 * what was written (the installer does so from its `.partial` gate).
 */
object TarStream {
    private const val MAX_MEMBER_BYTES = 256L * 1024L * 1024L
    private const val MAX_MEMBERS = 100_000

    /**
     * Scans every member of [stream] and validates it; nothing is written. The
     * stream is consumed fully (payloads skipped).
     */
    fun scan(stream: InputStream): List<TarMember> {
        val members = mutableListOf<TarMember>()
        val reader = TarReader(stream)
        while (true) {
            val header = reader.nextHeader() ?: break
            val member = validatedMember(header)
            if (members.size >= MAX_MEMBERS) throw TarStreamException("too many members")
            reader.skipPayload(header.size)
            members += member
        }
        return members
    }

    /**
     * Streams [stream] into [destRoot], creating directories, regular files
     * (streamed with a 64 KiB buffer, never fully buffered) and symlink entries.
     * Each member is validated before it is written.
     */
    fun extract(
        stream: InputStream,
        destRoot: File,
    ): TarExtraction {
        val members = mutableListOf<TarMember>()
        var bytes = 0L
        val reader = TarReader(stream)
        while (true) {
            val header = reader.nextHeader() ?: break
            val member = validatedMember(header)
            if (members.size >= MAX_MEMBERS) throw TarStreamException("too many members")
            writeMember(destRoot, member, reader)
            bytes += member.size
            members += member
        }
        return TarExtraction(members, bytes)
    }

    private fun validatedMember(header: MemberHeader): TarMember {
        val path = TarPathSafety.normalizeMemberPath(header.name)
        val (type, linkTarget) =
            when (header.typeFlag) {
                '0', '\u0000' -> {
                    TarMemberType.FILE to null
                }

                '5' -> {
                    TarMemberType.DIR to null
                }

                '2' -> {
                    TarMemberType.SYMLINK to
                        TarPathSafety.validateSymlinkTarget(
                            header.linkName.orEmpty(),
                            path,
                        )
                }

                else -> {
                    throw TarStreamException("unsupported tar member type '${header.typeFlag}'")
                }
            }
        // The archive root ("" from "/", "./", "/./") may only be a directory;
        // oversized members are refused before any I/O.
        val problem =
            when {
                path.isEmpty() && type != TarMemberType.DIR -> {
                    "non-directory member at the archive root: ${header.name}"
                }

                header.size > MAX_MEMBER_BYTES -> {
                    "member too large: $path"
                }

                else -> {
                    null
                }
            }
        if (problem != null) throw TarStreamException(problem)
        return TarMember(path, type, header.size, header.mode, linkTarget)
    }

    private fun writeMember(
        destRoot: File,
        member: TarMember,
        reader: TarReader,
    ) {
        val target = File(destRoot, member.path)
        // Validate (and for DIRs, materialize) before any payload I/O so a late
        // failure never leaves a half-written tree that looks valid.
        val problem = writeMemberProblem(target, member)
        if (problem != null) throw TarStreamException(problem)
        when (member.type) {
            TarMemberType.DIR -> {
                Unit
            }

            TarMemberType.FILE -> {
                target.parentFile?.mkdirs()
                FileOutputStream(target).use { out ->
                    reader.copyPayload(out, member.size)
                }
                bestEffortMode(target, member.mode)
            }

            TarMemberType.SYMLINK -> {
                // Pre-validated non-null above; error() is unreachable but keeps
                // the smart-cast-free shape explicit.
                val linkTarget = member.linkTarget ?: error("symlink without target: ${member.path}")
                target.parentFile?.mkdirs()
                // Paths.get (public API 26+) — NOT java.nio.file.Path.of, which is
                // @SystemApi below Android 13 (API 33) and throws NoSuchMethodError
                // on API 29/31 devices.
                Files.createSymbolicLink(
                    target.toPath(),
                    java.nio.file.Paths
                        .get(linkTarget),
                )
            }
        }
    }

    /** Pre-flight problem for one member, or null when the write may proceed. */
    private fun writeMemberProblem(
        target: File,
        member: TarMember,
    ): String? =
        when {
            member.type == TarMemberType.DIR && !target.mkdirs() && !target.isDirectory -> {
                "cannot create directory: ${member.path}"
            }

            member.type == TarMemberType.SYMLINK && member.linkTarget == null -> {
                "symlink without target: ${member.path}"
            }

            member.type == TarMemberType.SYMLINK &&
                Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS) -> {
                "symlink already exists: ${member.path}"
            }

            else -> {
                null
            }
        }

    private fun bestEffortMode(
        file: File,
        mode: Int,
    ) {
        // App-private Android storage largely ignores mode bits; the execute-bit
        // story for PRoot binaries is owned by the runner (HXA-084). Never fail
        // the install on a rejected chmod.
        runCatching {
            file.setReadable(true, false)
            file.setWritable(true, false)
            file.setExecutable((mode and 0x100) != 0, false)
        }
    }

    private class MemberHeader(
        val name: String,
        val typeFlag: Char,
        val size: Long,
        val mode: Int,
        val linkName: String?,
    )

    private class TarReader(
        private val stream: InputStream,
    ) {
        private val block = ByteArray(BLOCK)
        private var pendingName: String? = null
        private var pendingLink: String? = null

        /** Returns the next regular/dir/symlink member, or null at end of archive. */
        fun nextHeader(): MemberHeader? {
            while (true) {
                if (!readBlock()) return null
                checkChecksum()
                val typeFlag = block[156].toInt().toChar()
                val name = headerField(block, 0, 100)
                val linkName = headerField(block, 157, 100)
                val size = headerOctal(block, 124, 12)
                val mode = headerOctal(block, 100, 8).toInt()
                // GNU long-name/longlink continuation: remember and keep scanning.
                if (typeFlag == 'L' || typeFlag == 'K') {
                    val longString = readLongString(size)
                    if (typeFlag == 'L') {
                        pendingName = longString
                    } else {
                        pendingLink = longString
                    }
                    continue
                }
                val header =
                    MemberHeader(
                        name = pendingName ?: name,
                        typeFlag = typeFlag,
                        size = size,
                        mode = mode,
                        linkName = pendingLink ?: (if (linkName.isEmpty()) null else linkName),
                    )
                pendingName = null
                pendingLink = null
                return header
            }
        }

        /** Skips [size] payload bytes plus block padding. */
        fun skipPayload(size: Long) {
            var remaining = size
            val buf = ByteArray(65536)
            while (remaining > 0) {
                val n = stream.read(buf, 0, minOf(buf.size, remaining.toInt()))
                if (n < 0) throw TarStreamException("truncated tar payload")
                remaining -= n
            }
            skipPadding(size)
        }

        /** Streams exactly [size] payload bytes into [out], then consumes the padding. */
        fun copyPayload(
            out: FileOutputStream,
            size: Long,
        ) {
            var remaining = size
            val buf = ByteArray(65536)
            while (remaining > 0) {
                val n = stream.read(buf, 0, minOf(buf.size, remaining.toInt()))
                if (n < 0) throw TarStreamException("truncated tar payload")
                out.write(buf, 0, n)
                remaining -= n
            }
            skipPadding(size)
        }

        private fun readLongString(size: Long): String {
            val buf = ByteArray(size.toInt())
            var off = 0
            while (off < buf.size) {
                val n = stream.read(buf, off, buf.size - off)
                if (n < 0) throw TarStreamException("truncated tar long string")
                off += n
            }
            skipPadding(size)
            return String(buf, Charsets.UTF_8).trimEnd('\u0000').trimEnd()
        }

        /**
         * Reads one raw block. A full zero block is the end-of-archive marker; the
         * marker's second zero block (or a clean stream end) is consumed too so the
         * caller has read EVERY archive byte (the installer hashes the full stream).
         * Any other partial final block is a TRUNCATED stream (fail-closed).
         */
        private fun readBlock(): Boolean {
            if (readExact(BLOCK) != BLOCK) throw TarStreamException("truncated tar stream")
            if (!isZeroBlock()) return true
            // End of archive: everything remaining must be zero blocks or a clean
            // stream end — consumed so the caller has read EVERY archive byte.
            // The first violation is recorded and reported once the walk stops.
            var trailing = ""
            while (trailing.isEmpty()) {
                val n = readExact(BLOCK)
                if (n == 0) return false
                if (n < BLOCK) {
                    trailing = "truncated end-of-archive marker"
                } else if (hasNonZero(n)) {
                    trailing = "non-zero block after end-of-archive"
                }
            }
            throw TarStreamException(trailing)
        }

        private fun isZeroBlock(): Boolean {
            var allZero = true
            for (b in block) {
                if (b != 0.toByte()) {
                    allZero = false
                    break
                }
            }
            return allZero
        }

        private fun hasNonZero(n: Int): Boolean {
            var found = false
            for (i in 0 until n) {
                if (block[i] != 0.toByte()) {
                    found = true
                    break
                }
            }
            return found
        }

        private fun checkChecksum() {
            val expected = headerOctal(block, 148, 8)
            var sum = 0L
            for (i in 0 until BLOCK) {
                sum += if (i in 148..155) 0x20 else (block[i].toInt() and 0xFF)
            }
            if (sum != expected) throw TarStreamException("tar header checksum mismatch")
        }

        private fun skipPadding(size: Long) {
            val pad = (BLOCK - (size % BLOCK)) % BLOCK
            if (pad > 0 && readExact(pad.toInt()) < pad.toInt()) {
                throw TarStreamException("truncated tar padding")
            }
        }

        private fun readExact(n: Int): Int {
            var off = 0
            while (off < n) {
                val r = stream.read(block, off, n - off)
                if (r < 0) return off
                off += r
            }
            return off
        }

        companion object {
            private const val BLOCK = 512
        }
    }
}

/** Reads a NUL/space-terminated text field of one raw 512-byte header block. */
private fun headerField(
    block: ByteArray,
    start: Int,
    len: Int,
): String = String(block, start, len, Charsets.UTF_8).trimEnd(' ').trimEnd()

/**
 * Parses an octal field. Real tar headers pad the field with a NUL (and a
 * space) AFTER the digits, so the value must stop at the first NUL or space
 * — a trailing-only trim would leave the NUL in the digit run.
 */
private fun headerOctal(
    block: ByteArray,
    start: Int,
    len: Int,
): Long {
    var value = 0L
    var any = false
    for (i in start until start + len) {
        val b = block[i]
        if (b == 0.toByte() || b == ' '.toByte()) break
        val d = (b.toInt() - '0'.toInt())
        if (d !in 0..7) throw TarStreamException("bad octal field at $start")
        value = value * 8 + d
        any = true
    }
    return if (any) value else 0L
}
