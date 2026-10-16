package com.helix.core.workspace

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64

class ReadWindowProgressTest {
    @Test fun zeroProgressHashReadFailsWithoutPublishingAnIdentity() {
        val input =
            object : java.io.InputStream() {
                override fun read() = 0

                override fun read(
                    bytes: ByteArray,
                    offset: Int,
                    length: Int,
                ) = 0
            }
        assertThrows(java.io.IOException::class.java) {
            ReadWindow.read(input, 8, 0, 4, includeSha256 = true)
        }
    }

    @Test fun tinyWindowsPreserveEveryByteAndAdvanceEvenInsideAMultibyteCharacter() {
        val bytes = "😀中".toByteArray()
        val output = java.io.ByteArrayOutputStream()
        var offset = 0L
        while (offset < bytes.size) {
            val window = ReadWindow.read(ByteArrayInputStream(bytes), bytes.size.toLong(), offset, 1)
            assertTrue(window.nextOffset > offset)
            output.write(Base64.getDecoder().decode(requireNotNull(window.base64)))
            offset = window.nextOffset
        }
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun incompleteUtf8AtEofIsOpaqueInsteadOfSilentlyDiscarded() {
        val bytes = byteArrayOf('a'.code.toByte(), 0xe2.toByte(), 0x82.toByte())
        val window = ReadWindow.read(ByteArrayInputStream(bytes), bytes.size.toLong(), 0, 20)
        assertTrue(window.eof)
        assertEquals(bytes.size.toLong(), window.consumedBytes)
        assertArrayEquals(bytes, Base64.getDecoder().decode(requireNotNull(window.base64)))
    }

    @Test fun invalidUnicodeScalarsRemainOriginalBytesInsteadOfReplacementCharacters() {
        // Overlong encodings, UTF-16 surrogates and values above U+10FFFF have continuation-shaped bytes.
        val sequences =
            listOf(
                listOf(0xe0, 0x80, 0x80),
                listOf(0xed, 0xa0, 0x80),
                listOf(0xf0, 0x80, 0x80, 0x80),
                listOf(0xf4, 0x90, 0x80, 0x80),
            )
        for (sequence in sequences) {
            val bytes = byteArrayOf('a'.code.toByte()) + sequence.map { it.toByte() }.toByteArray()
            val window = ReadWindow.read(ByteArrayInputStream(bytes), bytes.size.toLong(), 0, 20)
            assertEquals(ContentProbe.Encoding.BINARY, window.encoding)
            assertEquals(bytes.size.toLong(), window.nextOffset)
            assertArrayEquals(bytes, Base64.getDecoder().decode(requireNotNull(window.base64)))
        }
    }
}
