package com.helix.core.storage.repository

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderId
import com.helix.core.policy.AutomationSessionScope
import com.helix.core.policy.BrowserTabScope
import com.helix.core.policy.DocumentTreeScope
import com.helix.core.policy.EgressTarget
import com.helix.core.policy.HighSensitivityRule
import com.helix.core.policy.RootSessionScope
import com.helix.core.policy.SharedStorageScope
import com.helix.core.policy.UserScope
import com.helix.core.policy.UserScopeCodec
import com.helix.core.policy.WorkspaceScope
import com.helix.core.storage.assertThrows
import com.helix.core.storage.assertThrowsAny
import com.helix.core.storage.dao.HighSensitivityRuleDao
import com.helix.core.storage.entity.HighSensitivityRuleEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * HXA-068 (ADR-0005): [HighSensitivityRuleRepository] — the storage path must round-trip a
 * [HighSensitivityRule] to a rehydrated rule that compares EQUAL (target, origin, scope, and
 * validity window), because the Policy Engine matches a stored rule by exact `scope == scope` and
 * any dropped field would silently break the auto-approval. A corrupt/undecodable row must fail
 * closed (never yield a partial or false rule set).
 */
class HighSensitivityRuleRepositoryTest {
    private val createdAt = Instant.parse("2026-01-01T00:00:00Z")
    private val origin = NormalizedEndpoint.parse("https://api.example.com/v1")
    private val target = EgressTarget.Provider(ProviderId("prov-1"))

    private fun rule(scope: UserScope): HighSensitivityRule =
        HighSensitivityRule.withDefaultTtl(target, origin, scope, createdAt)

    @Test
    fun saveRoundTripsEveryScopeSubtypeToAnEqualRule() {
        // Every scope subtype — including the two whose display-only fields (SAF display name,
        // root-session start time) the LOSSY toScopeRef drops — must come back EQUAL through the
        // full entity + lossless-codec + origin + TTL storage path.
        val scopes: List<UserScope> =
            listOf(
                WorkspaceScope("ws-1"),
                DocumentTreeScope("content://com.example.documents/tree/doc123", "My Photos"),
                SharedStorageScope(listOf("/sdcard/Movies", "/sdcard/Download")),
                BrowserTabScope("tab-1", 3),
                AutomationSessionScope(
                    setOf("com.example.app", "com.example.other"),
                    setOf("com.malicious.app"),
                    50,
                    createdAt,
                ),
                RootSessionScope(createdAt, createdAt.plusSeconds(600), true),
            )
        scopes.forEach { scope ->
            val dao = FakeEgressRuleDao()
            val original = rule(scope)
            val stored = HighSensitivityRuleRepository(dao).save(original)
            val rehydrated = HighSensitivityRuleRepository(dao).byId(stored.id).rule
            assertEquals("rehydrated rule must equal the saved rule (scope=$scope)", original, rehydrated)
        }
    }

    @Test
    fun saveStoresEveryFacetLosslessly() {
        val dao = FakeEgressRuleDao()
        val repo = HighSensitivityRuleRepository(dao)
        val scope = DocumentTreeScope("content://x/tree/y", "Renamed Later")
        repo.save(rule(scope))
        val entity = dao.rows.single()
        assertEquals("provider", entity.targetKind)
        assertEquals("prov-1", entity.targetId)
        assertEquals(origin.full, entity.originFull)
        // The display name survives the entity (unlike toScopeRef): the lossless codec keeps it.
        assertEquals(scope, UserScopeCodec.decode(entity.scopeEncoded))
        assertEquals(createdAt.epochSecond, entity.createdAtEpoch)
        // withDefaultTtl = 24h; whole-epochSecond storage preserves the exact fixed TTL.
        assertEquals(createdAt.plusSeconds(24 * 3600).epochSecond, entity.expiresAtEpoch)
    }

    @Test
    fun revokeRemovesTheRowAndFailsClosedOnUnknownId() {
        val dao = FakeEgressRuleDao()
        val repo = HighSensitivityRuleRepository(dao)
        val stored = repo.save(rule(WorkspaceScope("ws-1")))
        repo.revoke(stored.id)
        assertEquals(0, dao.rows.size)
        assertThrows("revoking a known id again must fail closed") { repo.revoke(stored.id) }
        assertThrows("revoking an unknown id must fail closed") { repo.revoke("does-not-exist") }
    }

    @Test
    fun allMapsEveryRowInDaoOrder() {
        val dao = FakeEgressRuleDao()
        val repo = HighSensitivityRuleRepository(dao)
        repo.save(rule(WorkspaceScope("ws-a")))
        repo.save(rule(WorkspaceScope("ws-b")))
        val listed = repo.all()
        assertEquals(2, listed.size)
        assertEquals(dao.rows.map { it.id }, listed.map { it.id })
    }

    @Test
    fun allFailsClosedOnAnUndecodableScope() {
        val dao = FakeEgressRuleDao()
        dao.rows += entity(scopeEncoded = "garbage-not-an-encoding")
        assertThrowsAny("undecodable scope must fail closed, not guess") {
            HighSensitivityRuleRepository(dao).all()
        }
    }

    @Test
    fun allFailsClosedOnAnUnknownTargetKind() {
        val dao = FakeEgressRuleDao()
        dao.rows += entity(targetKind = "ftp")
        assertThrowsAny("unknown target kind must fail closed") {
            HighSensitivityRuleRepository(dao).all()
        }
    }

    @Test
    fun allFailsClosedOnABrokenOrigin() {
        val dao = FakeEgressRuleDao()
        dao.rows += entity(originFull = "ftp://api.example.com")
        assertThrows("non-http/https origin must fail closed (parse is fail-closed)") {
            HighSensitivityRuleRepository(dao).all()
        }
    }

    @Test
    fun allFailsClosedWhenTheStoredTtlIsNotOneOfTheFixedTtls() {
        // A row whose window is neither 1h/24h/7d/30d (e.g. tampered) must be refused by the
        // HighSensitivityRule invariants on rehydration — never a silent, wrong-granularity rule.
        val dao = FakeEgressRuleDao()
        dao.rows += entity(createdAtEpoch = createdAt.epochSecond, expiresAtEpoch = createdAt.epochSecond + 5 * 3600)
        assertThrows("a 5h window is not a legal ADR-0005 TTL") {
            HighSensitivityRuleRepository(dao).all()
        }
    }

    // Builds a storage row around a valid rule, overriding the named facet to corrupt it.
    private fun entity(
        targetKind: String = "provider",
        targetId: String = "prov-1",
        originFull: String = origin.full,
        scopeEncoded: String = UserScopeCodec.encode(WorkspaceScope("ws-1")),
        createdAtEpoch: Long = createdAt.epochSecond,
        expiresAtEpoch: Long = createdAt.plusSeconds(24 * 3600).epochSecond,
    ): HighSensitivityRuleEntity =
        HighSensitivityRuleEntity(
            id = "row-1",
            targetKind = targetKind,
            targetId = targetId,
            originFull = originFull,
            scopeEncoded = scopeEncoded,
            createdAtEpoch = createdAtEpoch,
            expiresAtEpoch = expiresAtEpoch,
        )

    private class FakeEgressRuleDao : HighSensitivityRuleDao {
        val rows = mutableListOf<HighSensitivityRuleEntity>()

        override fun insert(rule: HighSensitivityRuleEntity) {
            rows += rule
        }

        override fun delete(id: String): Int {
            val before = rows.size
            rows.removeAll { it.id == id }
            return before - rows.size
        }

        override fun byId(id: String): HighSensitivityRuleEntity? = rows.firstOrNull { it.id == id }

        override fun list(): List<HighSensitivityRuleEntity> = rows.toList()
    }
}
