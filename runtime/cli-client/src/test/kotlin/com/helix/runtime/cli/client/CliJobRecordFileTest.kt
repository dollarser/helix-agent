package com.helix.runtime.cli.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files

class CliJobRecordFileTest {
    private val record = CliModelJobRecord("job_abcdef000001", "a".repeat(64), CliModelJobState.RUNNING, 1)

    @Test fun atomicReplacementFailurePreservesOldBytesAndNewTargetStaysAbsent() {
        val root = Files.createTempDirectory("cli-atomic").toFile()
        try {
            val file = root.resolve("record.json")
            CliJobRecordFile().write(file, record)
            val oldBytes = file.readText()
            val broken = CliJobRecordFile { _, _ -> throw IOException("replace unavailable") }
            val next = record.copy(state = CliModelJobState.CANCEL_REQUESTED)
            assertThrows(IOException::class.java) { broken.write(file, next) }
            assertEquals(oldBytes, file.readText())
            val absent = root.resolve("new.json")
            assertThrows(IOException::class.java) { broken.write(absent, next) }
            assertFalse(absent.exists())
            assertEquals(listOf("record.json"), root.list()!!.toList())
            CliJobRecordFile().write(file, next)
            assertEquals(next, CliJobRecordFile().read(file))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun growthIsBoundedToLimitPlusOneByteEvenAfterPreflight() {
        val input = ByteArrayInputStream(ByteArray(CliModelJobRecordCodec.MAX_RECORD_BYTES + 1000) { 32 })
        assertThrows(IllegalArgumentException::class.java) { CliJobRecordFile().decode(input) }
        assertEquals(999, input.available())
    }

    @Test fun oversizedSparseFileInvalidUtf8AndSymlinksAreRejected() {
        val root = Files.createTempDirectory("cli-invalid-record").toFile()
        try {
            val file = root.resolve("record.json")
            java.io.RandomAccessFile(file, "rw").use { it.setLength(128L * 1024 * 1024) }
            val io = CliJobRecordFile()
            assertThrows(IllegalArgumentException::class.java) { io.read(file) }
            file.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertThrows(java.nio.charset.CharacterCodingException::class.java) { io.read(file) }
            io.write(file, record)
            val link = root.resolve("link.json")
            Files.createSymbolicLink(link.toPath(), file.toPath())
            assertThrows(IllegalArgumentException::class.java) { io.read(link) }
            assertEquals(record, io.read(file))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun exactLimitIsReadableButMalformedFilesAreNotAbsent() {
        val root = Files.createTempDirectory("cli-bounded").toFile()
        try {
            val file = root.resolve("record.json")
            val io = CliJobRecordFile()
            assertNull(io.read(file))
            file.writeText(CliModelJobRecordCodec.encode(record).padEnd(CliModelJobRecordCodec.MAX_RECORD_BYTES, ' '))
            assertEquals(record, io.read(file))
            file.appendText(" ")
            assertThrows(IllegalArgumentException::class.java) { io.read(file) }
            file.writeText("{bad")
            assertThrows(IllegalArgumentException::class.java) { io.read(file) }
            file.delete()
            file.mkdir()
            assertThrows(IllegalArgumentException::class.java) { io.read(file) }
        } finally {
            root.deleteRecursively()
        }
    }
}
