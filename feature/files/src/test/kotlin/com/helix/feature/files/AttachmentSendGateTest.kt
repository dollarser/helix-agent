package com.helix.feature.files

import com.helix.core.model.AttachmentCategory
import com.helix.core.model.TextAttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.security.MessageDigest

/**
 * HXA-049: [AttachmentSendGate] — the fail-closed send gate over staged attachments. Covers the
 * no-attachments passthrough, all-text ready (in staged order), the closed-unsupported block, the
 * snapshot-broken fail-closed block (tampered / missing), the FULL-content credential refusal
 * (ADR-0014 §5, including past the bounded inline view), deterministic first-failure ordering, and
 * the closed per-message cap.
 */
class AttachmentSendGateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** No credential shape in the content — the clean-file tests. */
    private val noCredentials: (String) -> String? = { null }

    /** The caller-injected credential scanner: flags any content carrying [SECRET]. */
    private val credentialLike: (String) -> String? = { content ->
        if (content.contains(SECRET)) CREDENTIAL_REASON else null
    }

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun staged(
        text: String,
        name: String,
    ): StagedAttachment {
        val file = tmp.root.toPath().resolve(name)
        Files.write(file, text.toByteArray(Charsets.UTF_8))
        return StagedAttachment(name, sha(text.toByteArray()), file)
    }

    private fun stagedBytes(
        bytes: ByteArray,
        name: String,
    ): StagedAttachment {
        val file = tmp.root.toPath().resolve(name)
        Files.write(file, bytes)
        return StagedAttachment(name, sha(bytes), file)
    }

    @Test
    fun noStagedAttachmentsReadyWithNone() {
        val decision = AttachmentSendGate.evaluate(emptyList(), noCredentials)
        assertEquals(AttachmentSendDecision.Ready(emptyList()), decision)
    }

    @Test
    fun allTextAttachmentsReadyInStagedOrder() {
        val decision =
            AttachmentSendGate.evaluate(listOf(staged("one", "a.txt"), staged("two", "b.md")), noCredentials)
        val ready = decision as AttachmentSendDecision.Ready
        assertEquals(2, ready.attachments.size)
        assertEquals("a.txt", ready.attachments[0].fileName)
        assertEquals(TextAttachmentKind.TXT, ready.attachments[0].kind)
        assertEquals("b.md", ready.attachments[1].fileName)
        assertEquals(TextAttachmentKind.MARKDOWN, ready.attachments[1].kind)
    }

    @Test
    fun anUnsupportedAttachmentBlocksTheSendWithItsClosedCategory() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val decision =
            AttachmentSendGate.evaluate(listOf(staged("ok", "a.txt"), stagedBytes(png, "p.png")), noCredentials)

        assertEquals(AttachmentSendDecision.UnsupportedType("p.png", AttachmentCategory.OTHER), decision)
    }

    @Test
    fun aUtf16AttachmentBlocksAsUnsupportedTextEncoding() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "x".toByteArray(Charsets.UTF_16LE)
        val decision = AttachmentSendGate.evaluate(listOf(stagedBytes(bytes, "u.txt")), noCredentials)

        assertEquals(AttachmentSendDecision.UnsupportedType("u.txt", AttachmentCategory.TEXT_ENCODING), decision)
    }

    @Test
    fun aTamperedAttachmentFailsClosedAsSnapshotBroken() {
        val original = "original".toByteArray()
        val file = tmp.root.toPath().resolve("t.txt")
        Files.write(file, original)
        val boundSha = sha(original)
        Files.write(file, "tampered".toByteArray(Charsets.UTF_8))

        val decision =
            AttachmentSendGate.evaluate(listOf(StagedAttachment("t.txt", boundSha, file)), noCredentials)
        assertEquals(AttachmentSendDecision.SnapshotBroken("t.txt", SnapshotKind.TAMPERED), decision)
    }

    @Test
    fun aMissingAttachmentFailsClosedAsSnapshotBroken() {
        val file = tmp.root.toPath().resolve("gone.txt")
        val decision =
            AttachmentSendGate.evaluate(
                listOf(StagedAttachment("gone.txt", sha("x".toByteArray()), file)),
                noCredentials,
            )

        assertEquals(AttachmentSendDecision.SnapshotBroken("gone.txt", SnapshotKind.MISSING), decision)
    }

    @Test
    fun aCredentialWithinTheInlineBoundIsRefusedAsCredentialDetected() {
        val decision = AttachmentSendGate.evaluate(listOf(staged("token: $SECRET here", "creds.txt")), credentialLike)

        assertEquals(AttachmentSendDecision.CredentialDetected("creds.txt", CREDENTIAL_REASON), decision)
    }

    @Test
    fun aCredentialBeyondTheInlineBoundIsRefusedBecauseTheFullContentIsScanned() {
        // ADR-0014 §5: the gate scans the FULL file content, not just the bounded 8 KiB inline
        // view — a credential past the bound is refused exactly like one in the first bytes.
        val content = "x".repeat(8_200) + SECRET
        val decision = AttachmentSendGate.evaluate(listOf(staged(content, "big.txt")), credentialLike)

        assertEquals(AttachmentSendDecision.CredentialDetected("big.txt", CREDENTIAL_REASON), decision)
    }

    @Test
    fun aCleanFileBeyondTheInlineBoundStaysReady() {
        val content = "y".repeat(9_000)
        val decision = AttachmentSendGate.evaluate(listOf(staged(content, "big-clean.txt")), noCredentials)

        val ready = decision as AttachmentSendDecision.Ready
        assertEquals(1, ready.attachments.size)
        assertEquals("big-clean.txt", ready.attachments[0].fileName)
        assertTrue(ready.attachments[0].truncated)
    }

    @Test
    fun theFirstProblemAttachmentInStagedOrderBlocks() {
        // The image comes first, so even though a.txt is fine the block names the image.
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val decision =
            AttachmentSendGate.evaluate(listOf(stagedBytes(png, "img.png"), staged("ok", "a.txt")), noCredentials)

        assertEquals(AttachmentSendDecision.UnsupportedType("img.png", AttachmentCategory.OTHER), decision)
    }

    @Test
    fun overThePerMessageLimitIsRefusedFailClosed() {
        val staged = (0 until 5).map { staged("x", "f$it.txt") }
        var thrown: Throwable? = null
        try {
            AttachmentSendGate.evaluate(staged, noCredentials)
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("an over-limit stage must be refused", thrown is IllegalArgumentException)
        assertTrue(thrown!!.message!!.contains("at most"))
    }

    private companion object {
        // A fixture secret: it matches the credential SHAPE the scanner looks for, while staying
        // strictly short of the repo's secret-gate length bound (never a real credential).
        const val SECRET = "sk-abcdefghijklmnopq"
        const val CREDENTIAL_REASON = "fixture-credential-detected"
    }
}
