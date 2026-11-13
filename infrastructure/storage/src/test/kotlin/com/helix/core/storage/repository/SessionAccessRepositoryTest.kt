package com.helix.core.storage.repository

import com.helix.core.storage.dao.SessionAccessDao
import com.helix.core.storage.dao.SessionAccessExecution
import com.helix.core.storage.dao.SessionAccessSummary
import com.helix.core.storage.dao.SessionHistoryDao
import com.helix.core.storage.entity.SessionAccessGrantEntity
import com.helix.core.storage.entity.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class SessionAccessRepositoryTest {
    private val caller = SessionHistoryCaller("source", "turn", "call", "session.status")

    @Test fun revokeAndRegrantNeverReviveCapturedScope() {
        val fixture = Fixture()
        fixture.repository.setGrant("source", "target", true, false)
        val access = fixture.repository.resolve(caller, "target")
        fixture.repository.setGrant("source", "target", false, false)
        unavailable { fixture.repository.validate(caller, access) }
        fixture.repository.setGrant("source", "target", true, false)
        unavailable { fixture.repository.validate(caller, access) }
        assertEquals(3L, fixture.repository.resolve(caller, "target").revision)
        assertEquals(3, fixture.dao.audits.size)
        assertEquals(
            "user",
            fixture.dao.audits
                .last()
                .actor,
        )
        assertEquals(
            "session.access_grant",
            fixture.dao.audits
                .last()
                .type,
        )
        assertTrue(
            fixture.dao.audits
                .last()
                .redactedPayload
                .contains("\"revision\":3"),
        )
    }

    @Test fun unknownAndUnauthorizedDoNotDiscloseExistence() {
        val fixture = Fixture()
        assertEquals(
            failure {
                fixture.repository.resolve(caller, "missing")
            },
            failure { fixture.repository.resolve(caller, "target") },
        )
    }

    @Test fun metadataGrantDoesNotPermitHistoryOrBecomeTransitive() {
        val fixture = Fixture()
        fixture.repository.setGrant("source", "target", false, true)
        val access = fixture.repository.resolve(caller, "target", metadataOnly = true)
        assertFalse(access.readHistory)
        assertTrue(access.manageMetadata)
        unavailable { fixture.repository.resolve(caller, "target") }
        unavailable { fixture.repository.validate(caller.copy(sessionId = "other"), access) }
    }

    @Test fun delegationOnlyExposesMetadataWithoutHistoryOrManagement() {
        val fixture = Fixture()
        fixture.repository.setGrant("source", "target", false, false, delegateTasks = true)
        val access = fixture.repository.resolve(caller, "target", metadataOnly = true)
        assertTrue(access.delegateTasks)
        assertFalse(access.readHistory)
        assertFalse(access.manageMetadata)
        assertEquals(listOf("target"), fixture.repository.list(caller).map { it.targetSessionId })
        unavailable { fixture.repository.resolve(caller, "target") }
        unavailable { fixture.repository.resolve(caller, "target", manage = true) }
        fixture.repository.setGrant("source", "target", false, false)
        unavailable { fixture.repository.validate(caller, access) }
        assertTrue(fixture.repository.list(caller).isEmpty())
    }

    @Test fun stoppedCallerCannotResolveOrReturnSavedData() {
        val fixture = Fixture()
        fixture.repository.setGrant("source", "target", true, false)
        val access = fixture.repository.resolve(caller, "target")
        fixture.running = false
        unavailable { fixture.repository.resolve(caller, "target") }
        unavailable { fixture.repository.status(caller, access) }
        unavailable { fixture.repository.list(caller) }
    }

    @Test fun filteredListRechecksRevocationBeforeReturning() {
        val fixture = Fixture()
        fixture.repository.setGrant("source", "target", true, false)
        fixture.dao.afterList = { fixture.repository.setGrant("source", "target", false, false) }
        unavailable { fixture.repository.list(caller) }
    }

    @Test fun executionMetadataIsGrantScopedAndRechecksRevocation() {
        val fixture = Fixture()
        fixture.repository.setGrant("source", "target", false, false, delegateTasks = true)
        val access = fixture.repository.resolve(caller, "target", metadataOnly = true)
        fixture.dao.targetExecution = SessionAccessExecution("target-turn", "WAITING_MODEL")
        assertEquals("target-turn", fixture.repository.execution(caller, access)?.turnId)
        fixture.dao.afterExecution = { fixture.repository.setGrant("source", "target", false, false) }
        unavailable { fixture.repository.execution(caller, access) }
    }

    @Test fun currentSessionUsesTrustedCallerWithoutGrant() {
        val fixture = Fixture()
        val access = fixture.repository.resolve(caller, "source", manage = true)
        assertEquals(0L, access.revision)
        fixture.repository.validate(caller, access)
        assertEquals(emptyList<SessionAccessSummary>(), fixture.repository.list(caller))
    }

    private fun unavailable(block: () -> Unit) {
        assertEquals("SESSION_DATA_UNAVAILABLE", failure(block))
    }

    private fun failure(block: () -> Unit): String? =
        try {
            block()
            throw AssertionError("Expected denial")
        } catch (invalid: IllegalArgumentException) {
            invalid.message
        }

    private class Fixture {
        var running = true
        val dao = FakeDao()
        private val history =
            Proxy.newProxyInstance(
                SessionHistoryDao::class.java.classLoader,
                arrayOf(SessionHistoryDao::class.java),
            ) { _, method, args ->
                when (method.name) {
                    "caller" -> if (running) session(args[0] as String) else null
                    "sourceSession" -> session(args[0] as String)
                    else -> error("Unexpected query ${method.name}")
                }
            } as SessionHistoryDao
        val repository = SessionAccessRepository(dao, history)

        private fun session(id: String) = if (id == "missing") null else SessionEntity(id, id, null, null, 0L, null)
    }

    private class FakeDao : SessionAccessDao {
        private val rows = mutableMapOf<Pair<String, String>, SessionAccessGrantEntity>()
        var afterList: () -> Unit = {}
        var afterExecution: () -> Unit = {}
        var targetExecution: SessionAccessExecution? = null

        override fun execution(
            target: String,
            terminalStates: List<String>,
        ): SessionAccessExecution? = targetExecution?.takeIf { it.state !in terminalStates }.also { afterExecution() }

        val audits = mutableListOf<com.helix.core.storage.entity.AuditEventEntity>()

        override fun appendGrantAudit(event: com.helix.core.storage.entity.AuditEventEntity) {
            audits.add(event)
        }

        override fun grant(
            source: String,
            target: String,
        ) = rows[source to target]

        override fun grants(source: String) = rows.values.filter { it.sourceSessionId == source }

        override fun put(grant: SessionAccessGrantEntity) {
            rows[grant.sourceSessionId to grant.targetSessionId] = grant
        }

        override fun list(
            source: String,
            afterId: String?,
            limit: Int,
        ): List<SessionAccessSummary> =
            grants(source)
                .filter {
                    (it.readHistory || it.manageMetadata || it.delegateTasks) &&
                        (afterId == null || it.targetSessionId > afterId)
                }.sortedBy { it.targetSessionId }
                .take(limit)
                .map {
                    SessionAccessSummary(
                        it.targetSessionId,
                        it.targetSessionId,
                        null,
                        it.readHistory,
                        it.manageMetadata,
                        it.revision,
                        it.delegateTasks,
                    )
                }.also { afterList() }

        override fun userRename(
            target: String,
            expectedTitle: String,
            title: String,
            searchTitle: String,
        ): Int = error("Unexpected write")

        override fun userArchive(
            target: String,
            expectedArchived: Boolean,
            archivedAt: Long?,
        ): Int = error("Unexpected write")

        override fun rename(
            source: String,
            target: String,
            revision: Long,
            turnId: String,
            callId: String,
            expectedTitle: String,
            title: String,
            searchTitle: String,
        ): Int = error("Unexpected write")

        override fun archive(
            source: String,
            target: String,
            revision: Long,
            turnId: String,
            callId: String,
            toolName: String,
            expectedArchived: Boolean,
            archivedAt: Long?,
        ): Int = error("Unexpected write")
    }
}
