package com.helix.app.goal

import com.helix.core.storage.content.FileContentStore
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.charset.CharacterCodingException

class EditedArtifactContentTest {
    @Test fun verifiesFullEditedTextAndKeepsScopeIdentity() {
        val bytes = "unchanged prefix\n新内容\nunchanged suffix".toByteArray()
        val expected = expectation(bytes)
        assertEquals("scope:app:output/result.txt", expected.path.toModelReference())
        assertArrayEquals(bytes, expected.verify(bytes))
    }

    @Test fun rejectsSameSizeChangesAndTruncatedBytes() {
        val bytes = "original".toByteArray()
        val expected = expectation(bytes)
        assertThrows(IllegalArgumentException::class.java) { expected.verify("modified".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { expected.verify(bytes.copyOf(3)) }
    }

    @Test fun requiresStrictWholeUtf8AndRejectsNul() {
        val invalid = byteArrayOf(0x61, 0xc3.toByte())
        assertThrows(CharacterCodingException::class.java) { expectation(invalid).verify(invalid) }
        val nul = byteArrayOf(0x61, 0)
        assertThrows(IllegalArgumentException::class.java) { expectation(nul).verify(nul) }
    }

    @Test fun rejectsOtherPathsSourcesAndOversizedResults() {
        val bytes = byteArrayOf(0x61)
        assertThrows(IllegalArgumentException::class.java) { expectation(bytes, "scope:app:output/other.txt") }
        assertThrows(IllegalArgumentException::class.java) { expectation(bytes, name = "write") }
        assertThrows(IllegalArgumentException::class.java) { expectation(bytes, version = "2") }
        assertThrows(IllegalArgumentException::class.java) { expectation(bytes, size = 1_048_577L) }
        assertThrows(IllegalArgumentException::class.java) { expectation(bytes, size = -1L) }
    }

    private fun expectation(
        bytes: ByteArray,
        path: String = "scope:app:output/result.txt",
        name: String = "edit",
        version: String = "1",
        size: Long = bytes.size.toLong(),
    ): EditedArtifactContent =
        EditedArtifactContent.parse(
            name,
            version,
            """{"path":"scope:app:output/result.txt","oldText":"old","newText":"new"}""",
            buildJsonObject {
                put("path", JsonPrimitive(path))
                put("sizeBytes", JsonPrimitive(size))
                put("sha256", JsonPrimitive(FileContentStore.sha256Hex(bytes)))
            }.toString(),
        )
}
