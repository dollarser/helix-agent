package com.helix.app.chat

import com.helix.core.model.AttachmentCategory
import com.helix.core.model.TextAttachmentKind
import com.helix.feature.files.AttachmentMaterialization
import com.helix.feature.files.AttachmentSendDecision
import com.helix.feature.files.SnapshotKind

/**
 * The no-auto-send send-admission policy for a chat send that carries staged attachments
 * (ADR-0014 §2/§5, HXA-049).
 *
 * This is the single choke point between "the user staged some attachments" and "a send reaches
 * the model". It is pure and co-tested (no device): the I/O of re-verifying each attachment's
 * bound SHA-256 and re-probing its bytes already happened in [com.helix.feature.files.AttachmentSendGate],
 * and its [AttachmentSendDecision] is passed in as input — [admit] decides only.
 *
 * The invariant (ADR-0014 §5, roadmap HXA-049 「用户只选择附件或系统分享进入会话时不得自动发送」):
 * selecting or staging an attachment NEVER reaches this policy on its own — only an EXPLICIT
 * send/confirm/retry calls it. A send is admitted ONLY when BOTH:
 * 1. the attachment gate is [AttachmentSendDecision.Ready] — fail-closed on any closed-unsupported
 *    type ([AttachmentSendDecision.UnsupportedType]) or a broken bound snapshot
 *    ([AttachmentSendDecision.SnapshotBroken], tampered or missing file); AND
 * 2. the egress disclosure allows it ([EgressDisclosure]: credential-shaped content is rejected in
 *    both profiles, and high-sensitivity file text is held for per-send confirmation — never
 *    auto-passed).
 *
 * The gate runs FIRST: a tampered or unsupported attachment blocks before any egress
 * consideration, because "is this even a valid, unmodified attachment?" must be settled before
 * "is this content allowed to leave?".
 */
object AttachmentSendAdmission {
    /**
     * The closed admission outcome for one explicit send.
     *
     * [Blocked] means the attachment gate refused the send — no egress is even evaluated and the
     * model is never reached. [Egress] means the gate is [AttachmentSendDecision.Ready] and the
     * [EgressDisclosure.Decision] governs what happens next (Proceed / Confirm / Rejected); it
     * also carries the materialized [Egress.attachments] (in staged order) so the caller can both
     * inject them into the model request and bind them to the turn.
     */
    sealed interface Outcome {
        /** The attachment gate blocked the send before any egress. [reason] is user-visible. */
        data class Blocked(
            val reason: String,
        ) : Outcome

        /**
         * The gate is Ready; the egress [decision] governs. [attachments] is the gate's
         * materialized attachments (text and images, in staged order) — the exact content the
         * caller puts into the outgoing request (image bytes via the bound
         * [com.helix.core.model.ImageReference], text via the bounded blocks).
         */
        data class Egress(
            val decision: EgressDisclosure.Decision,
            val attachments: List<AttachmentMaterialization>,
        ) : Outcome
    }

    /**
     * Admits or blocks one explicit send of [text] plus the gate-materialized [gate].
     *
     * Order is fixed: (1) the attachment gate — any non-Ready branch fails the send before the
     * content is disclosed: a broken snapshot or unsupported type returns [Outcome.Blocked] with
     * a user-visible reason and no egress, and a credential found in the FULL file content
     * ([AttachmentSendDecision.CredentialDetected]) returns [Outcome.Egress] carrying the refusal
     * [EgressDisclosure.Decision.Rejected] — a refusal, exactly like a credential typed in the
     * box, never Proceed or Confirm (ADR-0014 §5); (2) the egress disclosure over the combined
     * outgoing content — the user text plus one [EgressDisclosure.OutgoingContent.FileText] per
     * Ready attachment (which forces high-sensitivity confirmation, never an auto-pass).
     *
     * A send with no text AND no Ready attachment is refused ([Outcome.Blocked]) so an empty send
     * can never reach the model, even if a caller forgets its own blank-text guard.
     */
    fun admit(
        gate: AttachmentSendDecision,
        text: String,
        target: EgressDisclosure.EgressTarget,
    ): Outcome {
        return when (gate) {
            is AttachmentSendDecision.Ready -> {
                if (text.isBlank() && gate.attachments.isEmpty()) {
                    return Outcome.Blocked("消息为空：请输入内容或至少选择一个可发送的附件")
                }
                val contents = outgoingContents(text, gate.attachments)
                Outcome.Egress(
                    EgressDisclosure.decide(contents, guardText(text, gate.attachments), target),
                    gate.attachments,
                )
            }

            is AttachmentSendDecision.UnsupportedType -> {
                Outcome.Blocked(unsupportedReason(gate.fileName, gate.category))
            }

            is AttachmentSendDecision.SnapshotBroken -> {
                Outcome.Blocked(snapshotBrokenReason(gate.fileName, gate.kind))
            }

            is AttachmentSendDecision.CredentialDetected -> {
                // A credential anywhere in the FULL attachment content is a REFUSAL — the same
                // outcome class as one typed in the box (ADR-0014 §5): never Proceed, never
                // Confirm, and the matched content is never echoed (only the guard reason).
                Outcome.Egress(EgressDisclosure.Decision.Rejected(gate.reason), emptyList())
            }
        }
    }

    /**
     * The outgoing-content sources for the egress gate: the user text (when present) plus one
     * [EgressDisclosure.OutgoingContent.FileText] per materialized attachment, carrying the
     * file's display facts (名称/类型/大小 — ADR-0014 §5). A FileText source maps to
     * [EgressDisclosure.DataCategory.HIGH_SENSITIVE_FILE_TEXT], so any attachment forces a
     * per-send confirmation — the attachment is never silently auto-passed (ADR-0014 §5).
     */
    private fun outgoingContents(
        text: String,
        attachments: List<AttachmentMaterialization>,
    ): List<EgressDisclosure.OutgoingContent> {
        val contents = mutableListOf<EgressDisclosure.OutgoingContent>()
        if (text.isNotBlank()) contents += EgressDisclosure.OutgoingContent.UserText
        for (a in attachments) {
            contents +=
                when (a) {
                    is AttachmentMaterialization.Text -> {
                        EgressDisclosure.OutgoingContent.FileText(
                            sourceLabel = a.fileName,
                            sizeBytes = a.sizeBytes,
                            sha256 = a.sha256,
                            kindLabel = kindLabel(a.kind),
                        )
                    }

                    is AttachmentMaterialization.Image -> {
                        // The normalized facts (the gate re-verified the normalized artifact):
                        // sizeBytes/sha256 bind the re-encoded bytes that leave the device.
                        EgressDisclosure.OutgoingContent.Image(
                            sourceLabel = a.fileName,
                            sizeBytes = a.sizeBytes,
                            sha256 = a.sha256,
                            mediaType = a.mediaType,
                            width = a.width,
                            height = a.height,
                        )
                    }

                    else -> {
                        // Unreachable (the gate only returns Text/Image in Ready) — fail closed.
                        error("non-materializable attachment reached admission")
                    }
                }
        }
        return contents
    }

    /**
     * The text the egress credential guard re-scans here: the user text plus each materialized
     * attachment's bounded inline view — exactly what this function's caller puts into the
     * outgoing request. This is a second, inline-only safety net: the FULL content of every
     * attachment was already scanned for credential shapes at the gate via the injected scanner
     * (ADR-0014 §5 「凭据类内容仍拒绝出网」), so a credential ANYWHERE in a file — including past
     * the 8 KiB inline bound — is rejected as [AttachmentSendDecision.CredentialDetected] before
     * this point, just like one typed in the box.
     */
    private fun guardText(
        text: String,
        attachments: List<AttachmentMaterialization>,
    ): String =
        buildString {
            if (text.isNotBlank()) append(text)
            for (a in attachments.filterIsInstance<AttachmentMaterialization.Text>()) {
                if (isNotEmpty()) append('\n')
                append(a.content)
            }
        }

    /** The short Chinese display label of a first-batch [TextAttachmentKind] (ADR-0014 §5). */
    private fun kindLabel(kind: TextAttachmentKind): String =
        when (kind) {
            TextAttachmentKind.TXT -> "纯文本"
            TextAttachmentKind.MARKDOWN -> "Markdown"
            TextAttachmentKind.CSV -> "CSV 表格"
            TextAttachmentKind.JSON -> "JSON"
        }

    private fun unsupportedReason(
        fileName: String,
        category: AttachmentCategory,
    ): String = "附件「$fileName」（${categoryLabel(category)}）暂不支持作为聊天输入，已阻止发送"

    private fun snapshotBrokenReason(
        fileName: String,
        kind: SnapshotKind,
    ): String =
        if (kind == SnapshotKind.TAMPERED) {
            "附件「$fileName」内容与导入时的快照不一致（可能已被修改），已阻止发送；请重新选择该文件"
        } else {
            "附件「$fileName」已不存在或无法读取，已阻止发送；请重新选择该文件"
        }

    /** The user-visible Chinese label of a closed unsupported [AttachmentCategory]. */
    private fun categoryLabel(category: AttachmentCategory): String =
        when (category) {
            AttachmentCategory.TEXT_ENCODING -> "非 UTF-8 文本编码"
            AttachmentCategory.DOCUMENT -> "文档"
            AttachmentCategory.AUDIO -> "音频"
            AttachmentCategory.VIDEO -> "视频"
            AttachmentCategory.OTHER -> "其他类型"
        }
}
