package com.helix.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.helix.app.chat.ChatSubmission
import com.helix.app.chat.ChatSubmissionReceipt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** An editor identity changes immediately; the database revision advances only on a successful CAS. */
internal class ConversationDraftBuffer(
    val sessionId: String,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    var value by mutableStateOf(ChatSubmission(sessionId, 0, newId(), ""))
        private set
    var saved by mutableStateOf<ChatSubmission?>(null)
        private set
    var ready by mutableStateOf(false)
        private set
    var revisionMessageId by mutableStateOf<String?>(null)
        private set
    var failed by mutableStateOf(false)
        private set
    var missingAttachments by mutableStateOf<List<String>>(emptyList())
        private set
    var sending by mutableStateOf(false)
    var attachmentsReady by mutableStateOf(false)
    private var edited = false
    private var pendingSave: ChatSubmission? = null
    private val gate = Mutex()

    val dirty: Boolean
        get() = value != saved && (saved != null || value.text.isNotEmpty() || value.attachmentIds.isNotEmpty())
    val editable: Boolean get() = ready && attachmentsReady && revisionMessageId == null
    val canSubmit: Boolean get() = !sending && missingAttachments.isEmpty()

    fun edit(text: String) {
        if (value.text == text || revisionMessageId != null) return
        edited = true
        value = value.copy(text = text, clientRequestId = newId())
    }

    fun attachments(ids: List<String>) {
        if (!attachmentsReady || sending || revisionMessageId != null) return
        val retained =
            value.attachmentIds.filter { it in ids || it in missingAttachments } +
                ids.filter { it !in value.attachmentIds }
        if (value.attachmentIds == retained) return
        edited = true
        value = value.copy(attachmentIds = retained, clientRequestId = newId())
    }

    fun restoredAttachments(missing: List<String>) {
        missingAttachments = missing.toList()
        attachmentsReady = true
    }

    fun discardMissingAttachments() {
        val remaining = value.attachmentIds - missingAttachments.toSet()
        missingAttachments = emptyList()
        attachments(remaining)
    }

    suspend fun initialize(load: suspend (String) -> ChatSubmission?): Boolean =
        guarded(false) {
            gate.withLock {
                val disk = load(sessionId)
                failed = false
                revisionMessageId = disk?.revisedMessageId
                if (revisionMessageId == null) {
                    if (!edited) value = disk ?: value
                    val knownDisk =
                        disk == saved || disk == pendingSave || disk?.clientRequestId == value.clientRequestId
                    if (!edited || knownDisk) {
                        saved = disk
                        pendingSave = null
                        if (disk?.clientRequestId == value.clientRequestId) value = requireNotNull(disk)
                    } else {
                        failed = true
                    }
                }
                ready = true
            }
            !failed
        }

    /** Serialize writes, preserve newer edits, and never claim that a rejected CAS was saved. */
    suspend fun persist(
        materialize: suspend (String) -> String?,
        load: suspend (String) -> ChatSubmission?,
        target: ChatSubmission = value,
        save: suspend (ChatSubmission, Long?) -> Boolean,
    ): Boolean =
        guarded(false) {
            gate.withLock {
                if (!ready || revisionMessageId != null) return@withLock false
                if (target == saved || (!dirty && target == value)) {
                    failed = false
                    return@withLock true
                }
                if (materialize(sessionId) != sessionId) {
                    failed = true
                    return@withLock false
                }
                val disk = load(sessionId)
                if (disk != saved && disk != pendingSave) {
                    failed = true
                    revisionMessageId = disk?.revisedMessageId
                    return@withLock false
                }
                saved = disk
                val snapshot = target.copy(revision = disk?.revision?.plus(1) ?: 0)
                pendingSave = snapshot
                if (!save(snapshot, disk?.revision)) {
                    failed = true
                    return@withLock false
                }
                saved = snapshot
                pendingSave = null
                if (value.clientRequestId == snapshot.clientRequestId) value = snapshot
                failed = false
                true
            }
        }

    suspend fun accepted(
        receipt: ChatSubmissionReceipt,
        acknowledge: suspend (ChatSubmissionReceipt) -> Boolean,
        load: suspend (String) -> ChatSubmission?,
    ) = guarded(Unit) {
        gate.withLock {
            if (receipt.submission.sessionId != sessionId) return@withLock
            acknowledge(receipt)
            val disk = load(sessionId)
            if (disk == null || disk == saved) saved = disk
            val request = receipt.submission
            if (value.clientRequestId == request.clientRequestId &&
                value.text == request.text && value.attachmentIds == request.attachmentIds
            ) {
                value = ChatSubmission(sessionId, 0, newId(), "")
                edited = false
                missingAttachments = emptyList()
            }
        }
    }

    suspend fun restore(block: suspend () -> Unit): Boolean =
        guarded(false) {
            block()
            true
        }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private suspend fun <T> guarded(
        fallback: T,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
            fallback
        }

    companion object {
        val Saver =
            listSaver<ConversationDraftBuffer, String>(
                save = { listOf(encode(it.value), encode(it.saved), encode(it.pendingSave), it.edited.toString()) },
                restore = { fields ->
                    val restored = requireNotNull(decode(fields[0]))
                    ConversationDraftBuffer(restored.sessionId).apply {
                        value = restored
                        saved = decode(fields[1])
                        pendingSave = decode(fields[2])
                        edited = fields[3].toBoolean()
                    }
                },
            )

        private fun encode(value: ChatSubmission?): String =
            if (value == null) {
                ""
            } else {
                Json.encodeToString(
                    listOf(
                        value.sessionId,
                        value.revision.toString(),
                        value.clientRequestId,
                        value.text,
                        Json.encodeToString(value.attachmentIds),
                        value.revisedMessageId.orEmpty(),
                    ),
                )
            }

        private fun decode(encoded: String): ChatSubmission? {
            if (encoded.isEmpty()) return null
            val parts = Json.decodeFromString<List<String>>(encoded)
            return ChatSubmission(
                parts[0],
                parts[1].toLong(),
                parts[2],
                parts[3],
                Json.decodeFromString(parts[4]),
                parts[5].ifEmpty { null },
            )
        }
    }
}
