package com.helix.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestContextManifestTest {
    @Test
    fun `role code conversion conforms to model roles`() {
        assertEquals('s', MessageRefEntry.fromModelRole(ModelRole.SYSTEM))
        assertEquals('u', MessageRefEntry.fromModelRole(ModelRole.USER))
        assertEquals('a', MessageRefEntry.fromModelRole(ModelRole.ASSISTANT))
        assertEquals('t', MessageRefEntry.fromModelRole(ModelRole.TOOL))
    }

    @Test
    fun `encodeCompact produces valid compact JSON format`() {
        val manifest =
            RequestContextManifest(
                callId = "call-123",
                timestamp = 1774300000000L,
                checkpoint = 42L,
                messages =
                    listOf(
                        MessageRefEntry("msg-1", 's'),
                        MessageRefEntry("msg-2", 'u'),
                        MessageRefEntry("msg-3", 'a'),
                    ),
                inputIds = listOf("in-1", "in-2"),
            )

        val json = CompactManifestCodec.encodeCompact(manifest)
        assertTrue(json.contains("\"c\":\"call-123\""))
        assertTrue(json.contains("\"t\":1774300000000"))
        assertTrue(json.contains("\"cp\":42"))
        assertTrue(json.contains("\"m\":[[\"msg-1\",\"s\"],[\"msg-2\",\"u\"],[\"msg-3\",\"a\"]]"))
        assertTrue(json.contains("\"i\":[\"in-1\",\"in-2\"]"))
        assertFalse(json.contains("\"tr\":true"))
    }

    @Test
    fun `512 messages and 512 UUID inputs produce compact line under 50 KiB`() {
        val messages =
            (1..512).map {
                MessageRefEntry("0123456789abcdef0123456789abcdef", if (it % 2 == 0) 'u' else 'a')
            }
        val inputs =
            (1..512).map {
                "550e8400-e29b-41d4-a716-446655440000"
            }

        val manifest =
            CompactManifestCodec.bounded(
                callId = "call-benchmark",
                timestamp = 1774300000000L,
                checkpoint = null,
                messages = messages,
                inputIds = inputs,
            )

        val json = CompactManifestCodec.encodeCompact(manifest)
        val bytes = json.toByteArray(Charsets.UTF_8).size

        // Typical size matches research estimate: ~41.6 kB
        assertTrue("Manifest size should be under 50 KiB, got $bytes bytes", bytes < 50 * 1024)
        assertFalse("512 UUID inputs should fit within 256 KiB without truncation", manifest.isTruncated)
    }

    @Test
    fun `bounded ensures line never exceeds 256 KiB on extreme Unicode stress`() {
        val longUnicodeString = "é".repeat(300) // 600 UTF-8 bytes each
        val messages =
            (1..512).map {
                MessageRefEntry("msg-$it", 'u')
            }
        val inputs =
            (1..1000).map {
                "$it-$longUnicodeString"
            }

        val manifest =
            CompactManifestCodec.bounded(
                callId = "call-stress",
                timestamp = 1774300000000L,
                checkpoint = null,
                messages = messages,
                inputIds = inputs,
            )

        val json = CompactManifestCodec.encodeCompact(manifest)
        val bytes = json.toByteArray(Charsets.UTF_8).size

        assertTrue(
            "Manifest bytes must be <= 256 KiB limit, was $bytes",
            bytes <= RequestContextManifest.MAX_SINGLE_LINE_BYTES,
        )
        assertTrue("Extreme input must be marked as truncated", manifest.isTruncated)
    }
}
