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
        val texts = ready.attachments.filterIsInstance<AttachmentMaterialization.Text>()
        assertEquals(2, ready.attachments.size)
        assertEquals(2, texts.size)
        assertEquals("a.txt", texts[0].fileName)
        assertEquals(TextAttachmentKind.TXT, texts[0].kind)
        assertEquals("b.md", texts[1].fileName)
        assertEquals(TextAttachmentKind.MARKDOWN, texts[1].kind)
    }

    @Test
    fun anUnsupportedAttachmentBlocksTheSendWithItsClosedCategory() {
        // A ZIP-magic binary (a .docx is detected as zip) is the closed OTHER category — a
        // magic-confirmed image is NOT unsupported anymore (it is the ImageAttachment branch).
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)
        val decision =
            AttachmentSendGate.evaluate(listOf(staged("ok", "a.txt"), stagedBytes(zip, "p.zip")), noCredentials)

        assertEquals(AttachmentSendDecision.UnsupportedType("p.zip", AttachmentCategory.OTHER), decision)
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
        val text = ready.attachments.single() as AttachmentMaterialization.Text
        assertEquals(1, ready.attachments.size)
        assertEquals("big-clean.txt", text.fileName)
        assertTrue(text.truncated)
    }

    @Test
    fun theFirstProblemAttachmentInStagedOrderBlocks() {
        // An image WITHOUT its normalized artifact (a staging normalization failure) comes
        // first, so even though a.txt is fine the block names the image — fail closed.
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val decision =
            AttachmentSendGate.evaluate(listOf(stagedBytes(png, "img.png"), staged("ok", "a.txt")), noCredentials)

        assertEquals(AttachmentSendDecision.SnapshotBroken("img.png", SnapshotKind.MISSING), decision)
    }

    @Test
    fun anImageWithItsVerifiedNormalizedArtifactIsReadyWithTheNormalizedFacts() {
        // HXA-055: the raw PNG verifies against its bound snapshot AND the normalized JPEG
        // verifies against ITS bound snapshot — the Ready branch carries the NORMALIZED
        // facts (hash/size/dimensions), which is what the message binds and the wire carries.
        val png = pngMagic()
        val normalized = "normalized-jpeg-bytes".toByteArray()
        val normalizedFile = tmp.root.toPath().resolve("normalized.jpg")
        Files.write(normalizedFile, normalized)
        val entry =
            StagedAttachment(
                fileName = "img.png",
                boundSha256 = sha(png),
                file = writeBytes(png, "raw.png"),
                normalizedFile = normalizedFile,
                normalizedSha256 = sha(normalized),
                mediaType = "image/jpeg",
                normalizedWidth = 512,
                normalizedHeight = 384,
            )
        val decision = AttachmentSendGate.evaluate(listOf(entry), noCredentials)
        val ready = decision as AttachmentSendDecision.Ready
        val image = ready.attachments.single() as AttachmentMaterialization.Image
        assertEquals("img.png", image.fileName)
        assertEquals("image/jpeg", image.mediaType)
        assertEquals(sha(normalized), image.sha256)
        assertEquals(normalized.size.toLong(), image.sizeBytes)
        assertEquals(512, image.width)
        assertEquals(384, image.height)
    }

    @Test
    fun aTamperedNormalizedArtifactFailsClosedAsSnapshotBroken() {
        // The raw file is fine but the normalized bytes changed since staging: the bytes that
        // would LEAVE the device no longer verify — block, exactly like a tampered text file.
        val png = pngMagic()
        val normalizedFile = tmp.root.toPath().resolve("normalized.jpg")
        Files.write(normalizedFile, "normalized-v1".toByteArray())
        val boundSha = sha("normalized-v1".toByteArray())
        Files.write(normalizedFile, "normalized-v2-tampered".toByteArray())
        val entry =
            StagedAttachment(
                fileName = "img.png",
                boundSha256 = sha(png),
                file = writeBytes(png, "raw.png"),
                normalizedFile = normalizedFile,
                normalizedSha256 = boundSha,
                mediaType = "image/jpeg",
                normalizedWidth = 1,
                normalizedHeight = 1,
            )
        val decision = AttachmentSendGate.evaluate(listOf(entry), noCredentials)
        assertEquals(AttachmentSendDecision.SnapshotBroken("img.png", SnapshotKind.TAMPERED), decision)
    }

    @Test
    fun aMissingNormalizedArtifactFailsClosedAsSnapshotBroken() {
        val png = pngMagic()
        val entry =
            StagedAttachment(
                fileName = "img.png",
                boundSha256 = sha(png),
                file = writeBytes(png, "raw.png"),
                normalizedFile = tmp.root.toPath().resolve("absent-normalized.jpg"),
                normalizedSha256 = sha("x".toByteArray()),
                mediaType = "image/jpeg",
                normalizedWidth = 1,
                normalizedHeight = 1,
            )
        val decision = AttachmentSendGate.evaluate(listOf(entry), noCredentials)
        assertEquals(AttachmentSendDecision.SnapshotBroken("img.png", SnapshotKind.MISSING), decision)
    }

    /** The 8-byte PNG magic — enough for the probe's magic table to confirm image/png. */
    private fun pngMagic() = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun writeBytes(
        bytes: ByteArray,
        name: String,
    ): java.nio.file.Path {
        val file = tmp.root.toPath().resolve(name)
        Files.write(file, bytes)
        return file
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
