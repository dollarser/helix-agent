package com.helix.app.chat

import com.helix.core.storage.entity.SessionEntity
import com.helix.feature.files.AttachmentClassifier

/** Owns the transient draft and its preparation lock. Persistence never performs model work. */
@Suppress("TooManyFunctions")
internal class ChatDraftStore {
    private val lock = Any()

    @Volatile var current: SessionDraft? = null
        private set

    @Volatile var preparing: Boolean = false
        private set

    fun open(session: SessionEntity): Boolean =
        synchronized(lock) {
            if (preparing) return@synchronized false
            current = SessionDraft(session)
            true
        }

    fun clear() =
        synchronized(lock) {
            if (!preparing) current = null
        }

    fun beginPreparation(id: String): Boolean =
        synchronized(lock) {
            if (preparing || current?.session?.id != id) return@synchronized false
            preparing = true
            true
        }

    fun finishPreparation() = synchronized(lock) { preparing = false }

    fun persist(
        openId: String?,
        text: String,
        fallbackTitle: String,
        save: (SessionEntity) -> Unit,
    ): List<DraftAttachment>? =
        synchronized(lock) {
            val draft = current ?: return@synchronized emptyList()
            if (draft.session.id != openId) return@synchronized null
            val title = draft.session.title.ifBlank { automaticSessionTitle(text).ifBlank { fallbackTitle } }
            save(draft.session.copy(title = title))
            current = null
            draft.attachments
        }

    fun rename(
        id: String,
        title: String,
    ) = update(id) {
        it.copy(session = it.session.copy(title = title.trim()))
    }

    fun directory(
        id: String,
        reference: String?,
    ) = update(id) {
        it.copy(session = it.session.copy(directoryRef = reference))
    }

    fun model(
        id: String,
        providerId: String,
        modelId: String,
    ) = update(id) {
        it.copy(session = it.session.copy(providerId = providerId, modelId = modelId))
    }

    fun addAttachment(
        id: String,
        attachment: DraftAttachment,
    ) = update(id) {
        if (it.attachments.size >= AttachmentClassifier.MAX_ATTACHMENTS_PER_MESSAGE) {
            it
        } else {
            it.copy(attachments = it.attachments + attachment)
        }
    }

    fun removeAttachment(id: String) =
        synchronized(lock) {
            if (!preparing) {
                current =
                    current?.let { draft ->
                        draft.copy(attachments = draft.attachments.filterNot { it.id == id })
                    }
            }
        }

    private fun update(
        id: String,
        transform: (SessionDraft) -> SessionDraft,
    ) = synchronized(lock) {
        val draft = current
        if (!preparing && draft?.session?.id == id) current = transform(draft)
    }
}
