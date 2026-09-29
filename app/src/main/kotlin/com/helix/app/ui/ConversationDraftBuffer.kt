package com.helix.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.helix.app.chat.ChatSubmission
import com.helix.app.chat.ChatSubmissionOutcome
import com.helix.app.chat.ChatSubmissionReceipt
import com.helix.app.chat.ComposerEditClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** Current user edits are authoritative; optional cache failures never disable plain-text input. */
@Suppress("TooManyFunctions") // Draft recovery keeps all state transitions behind one serialized owner.
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
    private var submitted: ChatSubmission? = null
    private val gate = Mutex()

    val dirty: Boolean
        get() =
            value != saved &&
                (
                    saved != null || value.text.isNotEmpty() || value.attachmentIds.isNotEmpty() ||
                        value.referenceSourceSessionId != null
                )
    val editable: Boolean get() = ready && attachmentsReady && revisionMessageId == null
    val canSubmit: Boolean get() = !sending && missingAttachments.isEmpty()
    val acceptedReceiptCandidate: ChatSubmission?
        get() = (submitted ?: saved)?.takeIf { it.revisedMessageId == null }

    /** Freeze exactly what was clicked; submitting does not depend on cache I/O. */
    fun captureSubmission(): ChatSubmission =
        value.copy(attachmentIds = value.attachmentIds.toList()).also { submitted = it }

    fun edit(text: String) {
        if (value.text == text || revisionMessageId != null) return
        edited = true
        value = value.copy(text = text, clientRequestId = newId(), revision = ComposerEditClock.next(value.revision))
    }

    fun delivery(
        delivery: com.helix.core.storage.repository.SessionInputDelivery,
        expectedTurnId: String?,
    ) {
        if (sending || revisionMessageId != null) return
        require((delivery == com.helix.core.storage.repository.SessionInputDelivery.STEER) == (expectedTurnId != null))
        if (value.delivery == delivery && value.expectedTurnId == expectedTurnId) return
        edited = true
        value =
            value.copy(
                delivery = delivery,
                expectedTurnId = expectedTurnId,
                clientRequestId = newId(),
                revision = ComposerEditClock.next(value.revision),
            )
    }

    fun attachments(ids: List<String>) {
        if (!attachmentsReady || sending || revisionMessageId != null) return
        val retained =
            value.attachmentIds.filter { it in ids || it in missingAttachments } +
                ids.filter { it !in value.attachmentIds }
        if (value.attachmentIds == retained) return
        edited = true
        value =
            value.copy(
                attachmentIds = retained,
                clientRequestId = newId(),
                revision = ComposerEditClock.next(value.revision),
            )
    }

    fun reference(
        sourceSessionId: String?,
        kind: com.helix.core.storage.repository.ConversationReferenceKind?,
    ) {
        if (sending || revisionMessageId != null) return
        require((sourceSessionId == null) == (kind == null))
        if (value.referenceSourceSessionId == sourceSessionId && value.referenceKind == kind) return
        edited = true
        value =
            value.copy(
                referenceSourceSessionId = sourceSessionId,
                referenceKind = kind,
                clientRequestId = newId(),
                revision = ComposerEditClock.next(value.revision),
            )
    }

    /** Reads service-owned attachment state only after any receipt acknowledgement has settled. */
    suspend fun synchronizeAttachments(read: () -> List<String>) =
        guarded(Unit) {
            gate.withLock { attachments(read()) }
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

    suspend fun initialize(load: suspend (String) -> ChatSubmission?): Boolean {
        guarded(Unit) {
            gate.withLock {
                val disk = load(sessionId)
                saved = disk
                // Never replace what the user has typed with a restored older snapshot.
                revisionMessageId = if (!edited) disk?.revisedMessageId else value.revisedMessageId
                if (!edited && disk != null && revisionMessageId == null) value = disk
                if (edited && disk != null && value.revision < disk.revision) {
                    value = value.copy(revision = ComposerEditClock.next(disk.revision))
                }
                failed = false
            }
        }
        // An absent or unreadable cache is not an unusable composer.
        ready = true
        return true
    }

    /** Best-effort write-through. There is no database version conflict to resolve. */
    suspend fun persist(save: suspend (ChatSubmission) -> Boolean): Boolean =
        guarded(false) {
            gate.withLock {
                if (!ready || revisionMessageId != null) return@withLock false
                if (!dirty) return@withLock true
                val snapshot = value
                if (!save(snapshot)) {
                    failed = true
                    return@withLock false
                }
                saved = snapshot
                failed = false
                true
            }
        }

    suspend fun accepted(
        receipt: ChatSubmissionReceipt,
        acknowledge: suspend (ChatSubmissionReceipt) -> Boolean,
        load: suspend (String) -> ChatSubmission?,
    ) = withContext(NonCancellable) {
        guarded(Unit) {
            gate.withLock {
                val wasAccepted =
                    receipt.outcome is ChatSubmissionOutcome.Accepted ||
                        receipt.outcome is ChatSubmissionOutcome.Enqueued
                if (receipt.submission.sessionId != sessionId || receipt.submission.revisedMessageId != null ||
                    !wasAccepted
                ) {
                    return@withLock
                }
                val cleared = acknowledge(receipt)
                val disk = if (cleared) null else load(sessionId)
                if (disk == null || disk == saved) saved = disk
                val request = receipt.submission
                if (submitted?.clientRequestId == request.clientRequestId) submitted = null
                if (matchesAcceptedValue(request)) {
                    value = ChatSubmission(sessionId, ComposerEditClock.next(value.revision), newId(), "")
                    edited = false
                    missingAttachments = emptyList()
                }
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

    private fun matchesAcceptedValue(request: ChatSubmission): Boolean =
        value.clientRequestId == request.clientRequestId &&
            value.text == request.text &&
            value.attachmentIds == request.attachmentIds &&
            value.referenceSourceSessionId == request.referenceSourceSessionId &&
            value.referenceKind == request.referenceKind

    companion object {
        val Saver =
            listSaver<ConversationDraftBuffer, String>(
                save = { listOf(encode(it.value), encode(it.saved), encode(it.submitted), it.edited.toString()) },
                restore = { fields ->
                    val restored = requireNotNull(decode(fields[0]))
                    ConversationDraftBuffer(restored.sessionId).apply {
                        value = restored
                        saved = decode(fields[1])
                        submitted = decode(fields[2])
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
                        value.delivery.name,
                        value.expectedTurnId.orEmpty(),
                        value.referenceSourceSessionId.orEmpty(),
                        value.referenceKind?.name.orEmpty(),
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
                com.helix.core.storage.repository.SessionInputDelivery
                    .valueOf(parts.getOrNull(6) ?: "QUEUE"),
                parts.getOrNull(7)?.ifEmpty { null },
                parts.getOrNull(8)?.ifEmpty { null },
                parts.getOrNull(9)?.ifEmpty { null }?.let {
                    com.helix.core.storage.repository.ConversationReferenceKind
                        .valueOf(it)
                },
            )
        }
    }
}
