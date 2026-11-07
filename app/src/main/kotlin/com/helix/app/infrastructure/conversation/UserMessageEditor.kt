package com.helix.app.infrastructure.conversation

import com.helix.app.agent.ContextCompaction
import com.helix.app.chat.MessageEditHistory
import com.helix.core.model.TurnState
import com.helix.core.storage.HelixStorage
import com.helix.feature.conversation.application.ConversationMessageEditor
import com.helix.feature.conversation.application.MessageEditResult
import com.helix.feature.conversation.application.MessageEditSnapshot
import com.helix.feature.conversation.application.attachment.AttachmentContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Trusted manual command: no Turn, submission, tool, Provider, draft or attachment staging is started. */
internal class UserMessageEditor(
    private val storage: HelixStorage,
) : ConversationMessageEditor {
    override fun load(
        sessionId: String,
        messageId: String,
    ): MessageEditSnapshot {
        var result: MessageEditSnapshot? = null
        storage.withTransaction { result = loadSnapshot(sessionId, messageId) }
        return requireNotNull(result)
    }

    private fun loadSnapshot(
        sessionId: String,
        messageId: String,
    ): MessageEditSnapshot {
        val row = storage.messages.resolve(messageId)
        require(row.sessionId == sessionId && row.role == "USER" && row.kind == "TEXT" && row.supersededBy == null)
        val body = storage.messages.readContentBounded(row, MessageEditHistory.MAX_BYTES).orEmpty()
        val count = storage.messageAttachments.listByMessage(messageId).size
        val references =
            storage.sessionInputs
                .forMessage(messageId)
                ?.knowledgeReferences
                .orEmpty()
        val prefix =
            com.helix.core.model.input.KnowledgeReferences.authoredPrefix(
                AttachmentContext.authoredPrefix(body, count),
                references,
                storage.sessionInputs
                    .forMessage(messageId)
                    ?.inputId
                    .orEmpty(),
            )
        return MessageEditSnapshot(sessionId, messageId, version(row.contentRef), prefix)
    }

    override fun save(
        snapshot: MessageEditSnapshot,
        text: String,
    ): MessageEditResult {
        require(text.isNotBlank() && text.toByteArray(Charsets.UTF_8).size <= MessageEditHistory.MAX_BYTES)
        var result = MessageEditResult.CHANGED
        storage.withTransaction {
            if (storage.turns.listBySession(snapshot.sessionId).any { !TurnState.valueOf(it.state).isTerminal } ||
                storage.sessionInputs.listPending(snapshot.sessionId).isNotEmpty()
            ) {
                result = MessageEditResult.BUSY
                return@withTransaction
            }
            val original = loadSnapshot(snapshot.sessionId, snapshot.messageId)
            if (original.contentVersion != snapshot.contentVersion) return@withTransaction
            if (original.text == text) {
                result = MessageEditResult.SAVED
                return@withTransaction
            }
            val row = storage.messages.resolve(snapshot.messageId)
            val previous = storage.messages.readContentBounded(row, MessageEditHistory.MAX_BYTES).orEmpty()
            val replacement =
                if (original.text.isEmpty() &&
                    !previous.startsWith("\n\n") &&
                    previous.isNotEmpty()
                ) {
                    "$text\n\n$previous"
                } else {
                    text + previous.substring(original.text.length)
                }
            require(replacement.toByteArray(Charsets.UTF_8).size <= MessageEditHistory.MAX_BYTES)
            val end =
                storage.messages
                    .listBySession(snapshot.sessionId)
                    .lastOrNull { it.role == "ASSISTANT" && it.kind == "TEXT" }
                    ?.sequence
            val ranges = MessageEditHistory.read(storage, snapshot.sessionId)
            val editId = UUID.randomUUID().toString()
            storage.messages.editUserText(
                snapshot.sessionId,
                snapshot.messageId,
                row.contentRef,
                replacement,
                editId,
            )
            // Checkpoints derived from the old text must not mask the user's change in a later explicit send.
            storage.messages.invalidateDerivedKind(snapshot.sessionId, ContextCompaction.KIND, editId)
            if (end != null && end > row.sequence) {
                storage.messages.append(
                    UUID.randomUUID().toString(),
                    snapshot.sessionId,
                    null,
                    "SYSTEM",
                    MessageEditHistory.KIND,
                    MessageEditHistory.encode(MessageEditHistory.merge(ranges, row.sequence..end)),
                )
            }
            recordAudit(snapshot, editId, row.contentRef)
            result = MessageEditResult.SAVED
        }
        return result
    }

    private fun version(reference: String?): String? =
        reference?.let {
            java.security.MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest(it.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }

    private fun recordAudit(
        snapshot: MessageEditSnapshot,
        editId: String,
        originalContentRef: String?,
    ) {
        storage.auditEvents.append(
            UUID.randomUUID().toString(),
            snapshot.sessionId,
            "user_message.edited",
            "USER",
            buildJsonObject {
                put("messageId", snapshot.messageId)
                put("retainedEvidenceId", editId)
                put("originalContentRef", originalContentRef)
                put("automaticSend", false)
            }.toString(),
            System.currentTimeMillis(),
        )
    }
}
