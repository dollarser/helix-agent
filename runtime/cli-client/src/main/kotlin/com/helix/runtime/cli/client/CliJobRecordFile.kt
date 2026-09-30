package com.helix.runtime.cli.client

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/** Shared bounded journal IO; failure never replaces an old record with a partial copy. */
class CliJobRecordFile(
    private val replace: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        Unit
    },
) {
    fun read(file: File): CliModelJobRecord? {
        val attributes =
            try {
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return null
            }
        require(attributes.isRegularFile && attributes.size() in 1..CliModelJobRecordCodec.MAX_RECORD_BYTES.toLong()) {
            "Invalid CLI journal file"
        }
        return Files.newInputStream(file.toPath(), LinkOption.NOFOLLOW_LINKS).use(::decode)
    }

    internal fun decode(input: InputStream): CliModelJobRecord {
        val bytes = ByteArray(CliModelJobRecordCodec.MAX_RECORD_BYTES + 1)
        var size = 0
        while (size < bytes.size) {
            val count = input.read(bytes, size, bytes.size - size)
            if (count < 0) break
            require(count > 0) { "CLI journal read made no progress" }
            size += count
        }
        require(size in 1..CliModelJobRecordCodec.MAX_RECORD_BYTES) { "CLI journal exceeds limit" }
        return CliModelJobRecordCodec.decode(bytes.decodeToString(0, size, throwOnInvalidSequence = true))
    }

    fun write(
        file: File,
        record: CliModelJobRecord,
    ) {
        val bytes = CliModelJobRecordCodec.encode(record).encodeToByteArray()
        val parent = requireNotNull(file.parentFile)
        Files.createDirectories(parent.toPath())
        val temporary = File.createTempFile("record-", ".pending", parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                output.fd.sync()
            }
            replace(temporary.toPath(), file.toPath())
        } finally {
            temporary.delete()
        }
    }
}
