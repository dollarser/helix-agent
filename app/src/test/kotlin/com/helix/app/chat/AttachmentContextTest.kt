package com.helix.app.chat

import com.helix.core.model.TextAttachmentKind
import com.helix.core.model.attachment.AttachmentMaterialization
import com.helix.feature.conversation.application.attachment.AttachmentContext
import com.helix.feature.conversation.application.attachment.AttachmentContextBlock
import com.helix.feature.files.AttachmentMaterializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-049 (ADR-0014 §4): [AttachmentContext] — the PURE model-visible injection of materialized
 * chat attachments. Every inlined body is bounded to [com.helix.core.model.input.InputAttachmentLimits.MAX_INLINE_TEXT_BYTES]
 * leading UTF-8 bytes (never more — the remainder is chunked-read through the path), every block is
 * source-labelled, UNTRUSTED-marked, hash-bound, and the ONLY path-shaped value is the scope-relative
 * workspace path (no `/`-prefixed absolute path ever appears). With no blocks the typed text is
 * returned unchanged (no pure-text regression).
 */
class AttachmentContextTest {
    @Test fun pdfMetadataKeepsAnExplicitSourceWithoutInjectingExtractedText() {
        for (truncated in listOf(false, true)) {
            val text =
                AttachmentMaterialization.Text(
                    "paper.pdf",
                    TextAttachmentKind.PDF,
                    "preview",
                    "a".repeat(64),
                    truncated,
                    900,
                )
            val block =
                AttachmentContextBlock.Text(
                    text,
                    com.helix.core.workspace.FileScopePath.fromModelReference(
                        "scope:attachment-scope:input/attachments/paper.pdf",
                    ),
                )
            val out = AttachmentContext.buildUserMessageContent("总结这份PDF", listOf(block))
            assertTrue(out.contains("scope:attachment-scope:input/attachments/paper.pdf"))
            assertTrue(out.contains("documents_inspect"))
            assertTrue(out.contains("正文未载入"))
            assertFalse(out.contains("preview"))
            assertFalse(out.contains("全文内联"))
            assertFalse(out.contains("可分块读取"))
            assertTrue(out.contains(AttachmentContext.UNTRUSTED_MARKER))
            assertFalse(out.contains("文档文本预览已截断"))
        }
    }

    @Test
    fun revisionRestoresAuthoredPrefixAndRejectsAmbiguousAttachmentMarkers() {
        val text = "Keep trailing space \n"
        val body = AttachmentContext.buildUserMessageContent(text, listOf(block("attachment", false)))
        assertEquals(text, AttachmentContext.authoredPrefix(body, 1))
        assertEquals(body, AttachmentContext.authoredPrefix(body, 0))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AttachmentContext.authoredPrefix("$body\n\n$body", 1)
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            AttachmentContext.authoredPrefix("missing attachment", 1)
        }
    }

    private val sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private val relativePath = "input/attachments/att_test123/notes.txt"

    private fun block(
        content: String,
        truncated: Boolean,
        fileName: String = "notes.txt",
        kind: TextAttachmentKind = TextAttachmentKind.TXT,
    ) = AttachmentContextBlock.Text(
        AttachmentMaterialization.Text(fileName, kind, content, sha, truncated, content.toByteArray().size.toLong()),
        com.helix.core.workspace
            .FileScopePath("app", relativePath),
    )

    private fun imageBlock(
        fileName: String = "photo.jpg",
        mediaType: String = "image/jpeg",
        width: Int = 1024,
        height: Int = 768,
        sizeBytes: Long = 123_456L,
    ) = AttachmentContextBlock.Image(
        fileName = fileName,
        mediaType = mediaType,
        sha256 = sha,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
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
        assertTrue(out.indexOf(AttachmentContext.UNTRUSTED_MARKER) < out.indexOf("notes.txt"))
        assertEquals("scope:app:$relativePath", pathValueOf(out))
    }

    @Test
    fun everyNonImageKindUsesMetadataOnlyRegardlessOfSize() {
        for (kind in TextAttachmentKind.entries) {
            for (content in listOf("NEVER_INLINE_MARKER", "🙂".repeat(5000))) {
                val out = AttachmentContext.buildUserMessageContent("", listOf(block(content, true, kind = kind)))
                assertFalse(out.contains(content.take(40)))
                assertTrue(out.contains("正文未载入"))
                assertEquals("scope:app:$relativePath", pathValueOf(out))
                assertFalse(out.contains("全文内联"))
            }
        }
    }

    @Test
    fun theOnlyPathShapedValueIsScopeRelativeNeverAbsolute() {
        val out = AttachmentContext.buildUserMessageContent("", listOf(block("正文", truncated = false)))
        val path = pathValueOf(out)
        assertEquals("scope:app:input/attachments/att_test123/notes.txt", path)
        assertFalse(path.startsWith("/"))
        // No line of the injected message ever leads with '/' (no host-absolute path leaks in).
        assertFalse(out.lineSequence().any { it.startsWith("/") })
    }

    @Test
    fun anImageBlockIsLabelledHashBoundAndCarriesNoPath() {
        // HXA-055: the image block is the stable model-visible description of the pixels that
        // travel as the message's image part — name, type, normalized size + dimensions, the
        // bound (normalized) hash, the UNTRUSTED marker — and NO path line (there is nothing
        // to chunk-read; the bytes are the image part itself).
        val out = AttachmentContext.buildUserMessageContent("看这张", listOf(imageBlock()))
        assertTrue(out.startsWith("看这张"))
        assertTrue(out.contains("photo.jpg"))
        assertTrue(out.contains("image/jpeg"))
        assertTrue(out.contains("1024x768"))
        assertTrue(out.contains(sha))
        assertTrue(out.contains(AttachmentContext.UNTRUSTED_MARKER))
        assertTrue(out.indexOf(AttachmentContext.UNTRUSTED_MARKER) < out.indexOf("photo.jpg"))
        assertFalse(out.contains("完整内容路径"))
        assertFalse(out.lineSequence().any { it.startsWith("/") })
    }

    @Test
    fun unknownImageDimensionsDoNotBecomeZeroSizedImageClaims() {
        for ((width, height) in listOf(0 to 0, 1024 to 0, 0 to 768, -1 to 768)) {
            val image = imageBlock(width = width, height = height)
            val out = AttachmentContext.buildUserMessageContent("Inspect", listOf(image))
            assertFalse(out.contains("归一化尺寸"))
            assertTrue(out.contains("归一化文件：${image.sizeBytes} 字节"))
            assertTrue(out.contains(sha))
            assertTrue(out.contains(AttachmentContext.UNTRUSTED_MARKER))
        }
    }

    @Test
    fun textAndImageBlocksCoexistInStagedOrder() {
        val out =
            AttachmentContext.buildUserMessageContent(
                "",
                listOf(block("正文", truncated = false), imageBlock()),
            )
        assertTrue(out.indexOf("notes.txt") < out.indexOf("photo.jpg"))
        assertTrue(out.contains("【附件 1/2"))
        assertTrue(out.contains("【附件 2/2"))
    }

    /** Extracts the value after the final ':' on the '完整内容路径' line — the model's read target. */
    private fun pathValueOf(out: String): String =
        out
            .lineSequence()
            .first { it.startsWith("附件路径（") }
            .substringAfterLast("：")
}
