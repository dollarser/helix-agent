package com.helix.core.storage.content

import com.helix.core.storage.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ContentRefTest {
    private val hash = "a".repeat(64)

    private fun path() = ContentRef.expectedPath(hash)

    @Test
    fun `accepts the content-addressed layout`() {
        val ref = ContentRef("content/aa/$hash", 12, hash)
        assertEquals("content/aa/$hash", ref.relativePath)
    }

    @Test
    fun `rejects non content-addressed paths including traversal`() {
        assertThrows("outside layout") { ContentRef("content/bb/$hash", 1, hash) }
        assertThrows("traversal") { ContentRef("content/${hash.substring(0, 2)}/../sibling", 1, hash) }
        assertThrows("absolute") { ContentRef("/etc/passwd", 1, hash) }
    }

    @Test
    fun `rejects bad hash and size`() {
        assertThrows("short hash") { ContentRef("content/aa/ab", 1, "ab") }
        val upper = "A".repeat(64)
        assertThrows("uppercase hash") { ContentRef("content/AA/$upper", 1, upper) }
        assertThrows("negative size") { ContentRef("content/aa/$hash", -1, hash) }
    }

    @Test
    fun `known vector round trip`() {
        val text = """{"path":"${path()}","size":42,"sha256":"$hash"}"""
        val ref = ContentRef.parse(text)
        assertEquals(hash, ref.sha256)
        assertEquals(42L, ref.size)
        assertEquals(path(), ref.relativePath)
        assertEquals(text, ref.toStorageString())
    }

    @Test
    fun `storage form round trips for any valid ref`() {
        val text = """{"path":"${path()}","size":1,"sha256":"$hash"}"""
        assertEquals(text, ContentRef.parse(text).toStorageString())
    }

    @Test
    fun `rejects malformed storage forms`() {
        val good = """{"path":"${path()}","size":1,"sha256":"$hash"}"""
        assertThrows("wrong field order") {
            ContentRef.parse("""{"size":1,"path":"${path()}","sha256":"$hash"}""")
        }
        assertThrows("missing field") { ContentRef.parse("""{"path":"${path()}","size":1}""") }
        assertThrows("extra field") {
            ContentRef.parse("""{"path":"${path()}","size":1,"sha256":"$hash","x":2}""")
        }
        assertThrows("float size") {
            ContentRef.parse("""{"path":"${path()}","size":1.0,"sha256":"$hash"}""")
        }
        assertThrows("duplicate key") {
            ContentRef.parse(
                """{"path":"${path()}","size":1,"sha256":"$hash","sha256":"$hash"}""",
            )
        }
        assertThrows("trailing content") { ContentRef.parse("$good x") }
        assertThrows("top level array") { ContentRef.parse("[]") }
        assertEquals(good, ContentRef.parse(good).toStorageString())
    }

    @Test
    fun `expectedPath derives the two level layout`() {
        assertFalse(ContentRef.expectedPath(hash).startsWith("//"))
        assertEquals("content/" + hash.substring(0, 2) + "/" + hash, ContentRef.expectedPath(hash))
    }
}
