package com.helix.app.chat

import com.helix.core.model.TextAttachmentKind
import com.helix.feature.files.AttachmentMaterialization
import com.helix.feature.files.AttachmentMaterializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-049 (ADR-0014 §4): [AttachmentContext] — the PURE model-visible injection of materialized
 * chat attachments. Every inlined body is bounded to [AttachmentMaterializer.MAX_INLINE_TEXT_BYTES]
 * leading UTF-8 bytes (never more — the remainder is chunked-read through the path), every block is
 * source-labelled, UNTRUSTED-marked, hash-bound, and the ONLY path-shaped value is the scope-relative
 * workspace path (no `/`-prefixed absolute path ever appears). With no blocks the typed text is
 * returned unchanged (no pure-text regression).
 */
class AttachmentContextTest {
    private val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val relativePath = "input/attachments/att_test123/notes.txt"

    private fun block(
        content: String,
        truncated: Boolean,
        fileName: String = "notes.txt",
        kind: TextAttachmentKind = TextAttachmentKind.TXT,
    ) = AttachmentContextBlock(
        fileName = fileName,
        kind = kind,
        content = content,
        sha256 = sha,
        truncated = truncated,
        sizeBytes = content.toByteArray().size.toLong(),
        relativePath = relativePath,
    )

    @Test
    fun noBlocksReturnsTheTypedTextUnchanged() {
        // No attachments: the pure-text send produces exactly today's model request (no regression).
        assertEquals("你好", AttachmentContext.buildUserMessageContent("你好", emptyList()))
    }

    @Test
    fun aBlockCarriesShaMarkerRelativePathAndKeepsTheTypedText() {
        val out = AttachmentContext.buildUserMessageContent("看看这份", listOf(block("正文", truncated = false)))
        assertTrue(out.startsWith("看看这份"))
        assertTrue(out.contains(sha))
        assertTrue(out.contains(AttachmentContext.UNTRUSTED_MARKER))
        assertTrue(out.contains("notes.txt"))
        assertEquals(relativePath, pathValueOf(out))
    }

    @Test
    fun theInlinedBodyIsBoundedToMaxInlineTextBytes() {
        // 10_000 'x' (1 byte each) exceeds the 8192-byte cap: only the leading 8192 bytes inline,
        // as ONE contiguous run (the isolated 'x' in the file name is not part of that run).
        val out = AttachmentContext.buildUserMessageContent("", listOf(block("x".repeat(10_000), truncated = true)))
        assertTrue(out.contains("x".repeat(AttachmentMaterializer.MAX_INLINE_TEXT_BYTES)))
        assertFalse(out.contains("x".repeat(AttachmentMaterializer.MAX_INLINE_TEXT_BYTES + 1)))
    }

    @Test
    fun theOnlyPathShapedValueIsScopeRelativeNeverAbsolute() {
        val out = AttachmentContext.buildUserMessageContent("", listOf(block("正文", truncated = false)))
        val path = pathValueOf(out)
        assertEquals("input/attachments/att_test123/notes.txt", path)
        assertFalse(path.startsWith("/"))
        // No line of the injected message ever leads with '/' (no host-absolute path leaks in).
        assertFalse(out.lineSequence().any { it.startsWith("/") })
    }

    @Test
    fun boundedPrefixTruncatesMultibyteCharsWithoutSplittingSurrogates() {
        // 5_000 emoji (U+1F642: 2 chars / 4 UTF-8 bytes each) = 20_000 bytes. The bound cuts at the
        // 8_192-byte mark: exactly 2_048 whole emoji, no torn surrogate pair.
        val bounded = AttachmentContext.boundedPrefix("🙂".repeat(5_000))
        assertTrue(bounded.startsWith("🙂"))
        val bytes = bounded.toByteArray().size
        assertTrue(bytes <= AttachmentMaterializer.MAX_INLINE_TEXT_BYTES)
        assertEquals(AttachmentMaterializer.MAX_INLINE_TEXT_BYTES, bytes)
        assertTrue(bounded.length % 2 == 0)
    }

    /** Extracts the value after the final ':' on the '完整内容路径' line — the model's read target. */
    private fun pathValueOf(out: String): String =
        out
            .lineSequence()
            .first { it.startsWith("完整内容路径") }
            .substringAfterLast("：")
}
