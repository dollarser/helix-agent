package com.helix.app.chat

import com.helix.core.model.AttachmentCategory
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import com.helix.core.model.TextAttachmentKind
import com.helix.feature.files.AttachmentMaterialization
import com.helix.feature.files.AttachmentSendDecision
import com.helix.feature.files.SnapshotKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HXA-049 (ADR-0014 §2/§5): [AttachmentSendAdmission] — the no-auto-send choke point. A send is
 * admitted only when the attachment gate is Ready AND the egress disclosure allows it. Covers the
 * pure-text no-regression passthrough, the per-send confirmation an attachment always forces (never
 * auto-pass), the credential guard reaching into attachment content (both the inline re-scan and
 * the gate's FULL-content [AttachmentSendDecision.CredentialDetected] refusal), the fail-closed
 * gate blocks (unsupported / tampered / missing), the empty-send guard, and the staged-order
 * attachment projection the caller binds to the turn.
 */
class AttachmentSendAdmissionTest {
    private val target =
        EgressDisclosure.EgressTarget(
            providerId = "prov_1",
            providerName = "OpenAI",
            protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            origin = "https://api.openai.com",
            residence = ProviderResidence.PUBLIC_CLOUD,
        )

    private fun att(
        fileName: String,
        content: String,
        kind: TextAttachmentKind = TextAttachmentKind.TXT,
    ) = AttachmentMaterialization.Text(
        fileName = fileName,
        kind = kind,
        content = content,
        sha256 = SHA,
        truncated = false,
        sizeBytes = content.toByteArray().size.toLong(),
    )

    @Test
    fun pureTextSendProceedsUnchanged() {
        // No attachments: the admission reproduces today's pure-text egress exactly — no regression.
        val outcome = AttachmentSendAdmission.admit(AttachmentSendDecision.Ready(emptyList()), "你好", target)
        val egress = outcome as AttachmentSendAdmission.Outcome.Egress
        assertTrue(egress.decision is EgressDisclosure.Decision.Proceed)
        assertEquals(0, egress.attachments.size)
    }

    @Test
    fun pureTextCredentialIsRejected() {
        val outcome =
            AttachmentSendAdmission.admit(
                AttachmentSendDecision.Ready(emptyList()),
                "key is sk-abcdefghijklmnopq",
                target,
            )
        val egress = outcome as AttachmentSendAdmission.Outcome.Egress
        assertTrue(egress.decision is EgressDisclosure.Decision.Rejected)
    }

    @Test
    fun aReadyTextAttachmentForcesPerSendConfirmNeverAutoPass() {
        val gate = AttachmentSendDecision.Ready(listOf(att("note.txt", "正文")))
        val outcome = AttachmentSendAdmission.admit(gate, "看看", target)
        val egress = outcome as AttachmentSendAdmission.Outcome.Egress
        val confirm = egress.decision as EgressDisclosure.Decision.Confirm
        // A FileText source is high-sensitivity: the send is HELD for per-send confirmation — it
        // must never Proceed (auto-pass) just because the gate is Ready.
        assertTrue(confirm.summary.categories.contains(EgressDisclosure.DataCategory.HIGH_SENSITIVE_FILE_TEXT))
    }

    @Test
    fun anAttachmentWithBlankTextStillConfirmsNotBlocked() {
        // An explicit send carrying ONLY an attachment (no typed text) is valid and still held for
        // confirmation — it is not an empty send and not auto-passed.
        val gate = AttachmentSendDecision.Ready(listOf(att("note.txt", "正文")))
        val outcome = AttachmentSendAdmission.admit(gate, "", target)
        val egress = outcome as AttachmentSendAdmission.Outcome.Egress
        assertTrue(egress.decision is EgressDisclosure.Decision.Confirm)
    }

    @Test
    fun aCredentialInsideAttachmentContentIsRejected() {
        // ADR-0014 §5 「凭据类内容仍拒绝出网」: the credential guard scans the attachment's inlined
        // text too, so a secret shaped to live inside a file is rejected, not just one typed in.
        val outcome =
            AttachmentSendAdmission.admit(
                AttachmentSendDecision.Ready(listOf(att("creds.txt", "key is sk-abcdefghijklmnopq"))),
                "帮我总结",
                target,
            )
        val egress = outcome as AttachmentSendAdmission.Outcome.Egress
        assertTrue(egress.decision is EgressDisclosure.Decision.Rejected)
    }

    @Test
    fun rejectionNeverEchoesTheMatchedAttachmentContent() {
        val secret = "sk-abcdefghijklmnopq"
        val egress =
            AttachmentSendAdmission.admit(AttachmentSendDecision.Ready(listOf(att("creds.txt", secret))), "hi", target)
                as AttachmentSendAdmission.Outcome.Egress
        val rejected = egress.decision as EgressDisclosure.Decision.Rejected
        assertTrue(secret !in rejected.reason)
    }

    @Test
    fun aFullFileCredentialDetectedAtTheGateIsARefusalNeverProceedOrConfirm() {
        // ADR-0014 §5: a credential found in the FULL attachment content (scanned at the gate)
        // is a REFUSAL — the same outcome class as one typed in the box: never Proceed, never
        // Confirm, the guard reason is carried, and no attachment materialization rides out.
        val gate =
            AttachmentSendDecision.CredentialDetected("creds.txt", "检测到凭据形态内容（API key / token / 密码 / 私钥），已拒绝发送；请移除后重试")
        val outcome = AttachmentSendAdmission.admit(gate, "帮我总结", target)
        val egress = outcome as AttachmentSendAdmission.Outcome.Egress
        val rejected = egress.decision as EgressDisclosure.Decision.Rejected
        assertEquals(gate.reason, rejected.reason)
        assertEquals(0, egress.attachments.size)
    }

    @Test
    fun anUnsupportedAttachmentBlocksBeforeAnyEgress() {
        val blocked =
            AttachmentSendAdmission.admit(
                AttachmentSendDecision.UnsupportedType("photo.png", AttachmentCategory.OTHER),
                "hi",
                target,
            ) as AttachmentSendAdmission.Outcome.Blocked
        assertTrue(blocked.reason.contains("photo.png"))
        assertTrue(blocked.reason.contains("不支持"))
    }

    @Test
    fun theUnsupportedReasonNamesTheClosedCategory() {
        val blocked =
            AttachmentSendAdmission.admit(
                AttachmentSendDecision.UnsupportedType("notes.txt", AttachmentCategory.TEXT_ENCODING),
                "hi",
                target,
            ) as AttachmentSendAdmission.Outcome.Blocked
        assertTrue(blocked.reason.contains("非 UTF-8 文本编码"))
    }

    @Test
    fun aTamperedSnapshotFailsClosedAndBlocks() {
        val blocked =
            AttachmentSendAdmission.admit(
                AttachmentSendDecision.SnapshotBroken("a.txt", SnapshotKind.TAMPERED),
                "hi",
                target,
            ) as AttachmentSendAdmission.Outcome.Blocked
        assertTrue(blocked.reason.contains("不一致"))
    }

    @Test
    fun aMissingSnapshotFailsClosedAndBlocks() {
        val blocked =
            AttachmentSendAdmission.admit(
                AttachmentSendDecision.SnapshotBroken("a.txt", SnapshotKind.MISSING),
                "hi",
                target,
            ) as AttachmentSendAdmission.Outcome.Blocked
        assertTrue(blocked.reason.contains("无法读取"))
    }

    @Test
    fun anEmptySendWithNoAttachmentIsBlocked() {
        // No typed text AND no Ready attachment: an empty send can never reach the model, even if a
        // caller forgets its own blank-text guard.
        val blocked =
            AttachmentSendAdmission.admit(AttachmentSendDecision.Ready(emptyList()), "", target)
                as AttachmentSendAdmission.Outcome.Blocked
        assertTrue(blocked.reason.contains("消息为空"))
    }

    @Test
    fun theReadyOutcomeCarriesTheMaterializedAttachmentsInStagedOrder() {
        val first = att("a.txt", "one")
        val second = att("b.md", "two", kind = TextAttachmentKind.MARKDOWN)
        val egress =
            AttachmentSendAdmission.admit(AttachmentSendDecision.Ready(listOf(first, second)), "hi", target)
                as AttachmentSendAdmission.Outcome.Egress
        assertEquals(listOf(first, second), egress.attachments)
    }

    private companion object {
        // The admission never verifies the hash (the gate does); a fixed 64-hex value is enough.
        const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
