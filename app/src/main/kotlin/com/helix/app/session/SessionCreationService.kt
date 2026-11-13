package com.helix.app.session

import com.helix.core.storage.entity.SessionCreationGrantEntity
import com.helix.core.storage.entity.SessionCreationReceiptEntity
import com.helix.core.storage.repository.SessionHistoryCaller

/** Inert session creation. The store transaction owns both creation and its durable receipt. */
class SessionCreationService(
    private val store: Store,
    private val create: (String) -> String,
    private val now: () -> Long,
) {
    interface Store {
        fun <T> transaction(action: () -> T): T

        fun validateCaller(caller: SessionHistoryCaller)

        fun grant(source: String): SessionCreationGrantEntity?

        fun saveGrant(grant: SessionCreationGrantEntity)

        fun auditGrant(
            source: String,
            allowed: Boolean,
            revision: Long,
            at: Long,
        )

        fun receipt(
            source: String,
            key: String,
        ): SessionCreationReceiptEntity?

        fun insert(receipt: SessionCreationReceiptEntity)
    }

    fun allowed(source: String): Boolean = store.grant(source)?.allowed == true

    /** UI-only command; normal tool registration never exposes this method. */
    fun setAllowed(
        source: String,
        allowed: Boolean,
    ) = store.transaction {
        val revision = Math.addExact(store.grant(source)?.revision ?: 0L, 1L)
        store.saveGrant(SessionCreationGrantEntity(source, allowed, revision))
        store.auditGrant(source, allowed, revision, now())
    }

    fun create(
        caller: SessionHistoryCaller,
        key: String,
        title: String,
        checkActive: () -> Unit = {},
    ): SessionCreationReceiptEntity {
        require(caller.toolName == "session.create")
        require(key.isNotBlank() && key.length <= 128 && '\u0000' !in key)
        require(title.isNotBlank() && title.length <= 200 && '\u0000' !in title)
        val canonical = title.trim()
        return store.transaction {
            checkActive()
            store.validateCaller(caller)
            val grant =
                requireNotNull(store.grant(caller.sessionId)?.takeIf { it.allowed }) {
                    "SESSION_CREATION_NOT_ALLOWED"
                }
            val previous = store.receipt(caller.sessionId, key)
            if (previous != null) {
                require(previous.title == canonical) { "SESSION_REQUEST_CONFLICT" }
                previous
            } else {
                val id = create(canonical)
                SessionCreationReceiptEntity(
                    caller.sessionId,
                    key,
                    canonical,
                    id,
                    caller.turnId,
                    caller.callId,
                    grant.revision,
                    now(),
                ).also(store::insert)
            }
        }
    }
}
