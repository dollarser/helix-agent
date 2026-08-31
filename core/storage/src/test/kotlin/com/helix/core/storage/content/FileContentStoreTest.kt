package com.helix.core.storage.content

import com.helix.core.storage.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FileContentStoreTest {
    @Test
    fun `write then read round trips content and verifies the hash`() {
        withTempRoot { root ->
            val store = FileContentStore(root)
            val ref = store.write("hello helix")
            assertTrue(store.exists(ref))
            assertEquals("hello helix", store.read(ref))
            assertEquals(ContentRef.expectedPath(ref.sha256), ref.relativePath)
        }
    }

    @Test
    fun `identical content is stored once at the same path`() {
        withTempRoot { root ->
            val store = FileContentStore(root)
            val first = store.write("same body")
            val second = store.write("same body")
            assertEquals(first, second)
            val file = File(root, first.relativePath)
            assertTrue(file.isFile)
        }
    }

    @Test
    fun `different content lands at different paths`() {
        withTempRoot { root ->
            val store = FileContentStore(root)
            val a = store.write("alpha")
            val b = store.write("beta")
            assertFalse(a.relativePath == b.relativePath)
        }
    }

    @Test
    fun `tampered file is rejected on read`() {
        withTempRoot { root ->
            val store = FileContentStore(root)
            val ref = store.write("original")
            File(root, ref.relativePath).writeText("tampered")
            assertThrows("hash mismatch") { store.read(ref) }
        }
    }

    @Test
    fun `size mismatch is rejected`() {
        withTempRoot { root ->
            val store = FileContentStore(root)
            val ref = store.write("original")
            val wrongSize = ref.copy(size = ref.size + 1)
            assertThrows("size mismatch") { store.read(wrongSize) }
        }
    }

    @Test
    fun `missing content is rejected`() {
        withTempRoot { root ->
            val store = FileContentStore(root)
            val ref = store.write("present")
            val otherHash = "b".repeat(64)
            val missing = ContentRef(ContentRef.expectedPath(otherHash), 1, otherHash)
            assertFalse(store.exists(missing))
            assertThrows("missing") { store.read(missing) }
        }
    }

    @Test
    fun `sha256Hex matches a known vector`() {
        val expected = "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
        assertEquals(expected, FileContentStore.sha256Hex("hello".toByteArray()))
    }

    private inline fun withTempRoot(block: (File) -> Unit) {
        val root = File.createTempFile("helix-content-store", "test")
        assertTrue(root.delete())
        assertTrue(root.mkdirs())
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
