package com.helix.app.session

import com.helix.core.storage.entity.SessionCreationGrantEntity
import com.helix.core.storage.entity.SessionCreationReceiptEntity
import com.helix.core.storage.repository.SessionHistoryCaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCreationServiceTest {
    private val caller = SessionHistoryCaller("source", "turn", "call", "session.create")

    @Test fun defaultDeniesAndDoesNotCreate() {
        val store = Store()
        var created = 0
        val service =
            SessionCreationService(store, {
                created++
                "target"
            }, { 1L })
        assertThrows(IllegalArgumentException::class.java) { service.create(caller, "key", "Title") }
        assertEquals(0, created)
    }

    @Test fun lostReplyReturnsSameReceiptAndDoesNotRecreate() {
        val store = Store()
        var created = 0
        val service =
            SessionCreationService(store, {
                created++
                "target"
            }, { 1L })
        service.setAllowed("source", true)
        val first = service.create(caller, "key", " Title ")
        val second =
            SessionCreationService(store, { error("must not recreate") }, { 2L })
                .create(caller.copy(turnId = "later", callId = "retry"), "key", "Title")
        assertEquals(first, second)
        assertEquals(1, created)
        assertEquals("turn", second.sourceTurnId)
        assertEquals("call", second.sourceCallId)
    }

    @Test fun changedPayloadAndRevokedDuplicateAreRejected() {
        val store = Store()
        val service = SessionCreationService(store, { "target" }, { 1L })
        service.setAllowed("source", true)
        service.create(caller, "key", "Title")
        assertThrows(IllegalArgumentException::class.java) { service.create(caller, "key", "Other") }
        service.setAllowed("source", false)
        assertThrows(IllegalArgumentException::class.java) { service.create(caller, "key", "Title") }
        assertEquals(2L, store.grants.getValue("source").revision)
    }

    @Test fun grantsAreSourceBoundAndCallerMustRemainActive() {
        val store = Store()
        val service = SessionCreationService(store, { "target" }, { 1L })
        service.setAllowed("source", true)
        assertThrows(IllegalArgumentException::class.java) {
            service.create(caller.copy(sessionId = "another"), "key", "Title")
        }
        store.active = false
        assertThrows(IllegalArgumentException::class.java) { service.create(caller, "key", "Title") }
        assertTrue(store.receipts.isEmpty())
    }

    private class Store : SessionCreationService.Store {
        val grants = mutableMapOf<String, SessionCreationGrantEntity>()
        val receipts = mutableMapOf<Pair<String, String>, SessionCreationReceiptEntity>()
        var active = true

        override fun <T> transaction(action: () -> T) = synchronized(this) { action() }

        override fun validateCaller(caller: SessionHistoryCaller) {
            require(active)
        }

        override fun grant(source: String) = grants[source]

        override fun saveGrant(grant: SessionCreationGrantEntity) {
            grants[grant.sourceSessionId] = grant
        }

        override fun auditGrant(
            source: String,
            allowed: Boolean,
            revision: Long,
            at: Long,
        ) = Unit

        override fun receipt(
            source: String,
            key: String,
        ) = receipts[source to key]

        override fun insert(receipt: SessionCreationReceiptEntity) {
            receipts[receipt.sourceSessionId to receipt.requestKey] = receipt
        }
    }
}
