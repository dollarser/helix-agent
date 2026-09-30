package com.helix.runtime.quickjs

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files

class JsBoundedOutputTest {
    @Test fun exactLengthReadAndMismatches() {
        val file = Files.createTempFile("js-output", ".json").toFile()
        try {
            val body = "{\"ok\":true}".toByteArray()
            file.writeBytes(body)
            assertArrayEquals(body, JsBoundedOutput.read(file, body.size.toLong(), 1024))
            assertThrows(IOException::class.java) { JsBoundedOutput.read(file, body.size + 1L, 1024) }
            assertThrows(IOException::class.java) { JsBoundedOutput.read(file, body.size - 1L, 1024) }
            assertThrows(IOException::class.java) { JsBoundedOutput.read(file, -1, 1024) }
        } finally {
            file.delete()
        }
    }

    @Test fun sparseOversizedOutputIsRejectedBeforeAllocation() {
        val file = Files.createTempFile("js-oversize", ".json").toFile()
        try {
            RandomAccessFile(file, "rw").use { it.setLength(1L shl 32) }
            assertThrows(IOException::class.java) { JsBoundedOutput.read(file, file.length(), 256 * 1024) }
            assertThrows(IOException::class.java) { JsBoundedOutput.read(file, 1, 256 * 1024) }
        } finally {
            file.delete()
        }
    }
}
