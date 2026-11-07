package com.helix.core.storage.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BoundedContentReadTest {
    @Test
    fun exactUtf8ByteLimitSucceedsAndOneLessFails() =
        withStore { _, store ->
            val text = "验证内容"
            val ref = store.write(text)
            assertEquals(text, store.readBounded(ref, text.toByteArray().size))
            assertThrows(IllegalArgumentException::class.java) { store.readBounded(ref, text.toByteArray().size - 1) }
        }

    @Test
    fun oversizedFileCannotHideBehindSmallMetadata() =
        withStore { root, store ->
            val ref = store.write("ok")
            File(root, ref.relativePath).writeBytes(ByteArray(8193))
            assertThrows(IllegalArgumentException::class.java) { store.readBounded(ref, 2) }
        }

    @Test
    fun sameSizeTamperingAndMissingBodyFail() =
        withStore { root, store ->
            val ref = store.write("ok")
            val file = File(root, ref.relativePath)
            file.writeText("no")
            assertThrows(IllegalArgumentException::class.java) { store.readBounded(ref, 2) }
            file.delete()
            assertThrows(IllegalArgumentException::class.java) { store.readBounded(ref, 2) }
        }

    @Test
    fun emptyBodyAndInvalidLimitsAreExplicit() =
        withStore { _, store ->
            val ref = store.write("")
            assertEquals("", store.readBounded(ref, 0))
            assertThrows(IllegalArgumentException::class.java) { store.readBounded(ref, -1) }
            assertThrows(IllegalArgumentException::class.java) { store.readBounded(ref, Int.MAX_VALUE) }
        }

    private fun withStore(block: (File, FileContentStore) -> Unit) {
        val root = Files.createTempDirectory("bounded-content").toFile()
        try {
            block(root, FileContentStore(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
