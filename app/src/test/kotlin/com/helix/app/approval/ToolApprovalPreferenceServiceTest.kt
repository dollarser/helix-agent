package com.helix.app.approval

import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope
import com.helix.core.policy.EffectiveToolPreference
import com.helix.core.policy.ToolApprovalExposure
import com.helix.core.policy.ToolApprovalReason
import com.helix.core.policy.ToolApprovalResolver
import com.helix.core.storage.dao.ToolApprovalPreferenceDao
import com.helix.core.storage.dao.ToolBaselineMetaDao
import com.helix.core.storage.dao.ToolRegistrationBaselineDao
import com.helix.core.storage.entity.ToolApprovalPreferenceEntity
import com.helix.core.storage.entity.ToolBaselineMetaEntity
import com.helix.core.storage.entity.ToolRegistrationBaselineEntity
import com.helix.core.storage.repository.ToolApprovalPreferenceRepository
import com.helix.core.storage.repository.ToolBaselineIdentity
import com.helix.core.storage.repository.ToolRegistrationBaselineRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The user tool-approval preference service (HXA-200, ADR-0052): the ONLY write path and the live
 * read seam the Dispatcher and Registry exposure filter share. This JVM test exercises the service
 * against an in-memory DAO so the set/remove/applicable threading and the shared-resolver collapse
 * are proven without a device; the REAL end-to-end behavior (real Room, Registry exposure,
 * dispatcher pre-start re-resolution, restart/migration) is the device test's job, not this one.
 *
 * The read seam now returns the collapsed [EffectiveToolPreference] WITH its provenance: an unset
 * tool is [EffectiveToolPreference.Unset] (it keeps its original policy handling) while a stored
 * ALLOW a contract change invalidated is an [EffectiveToolPreference.Ask] tagged
 * [ToolApprovalReason.ALLOW_INVALIDATED] — the 2026-09-14 clarification to ADR-0052 point 1.
 */
class ToolApprovalPreferenceServiceTest {
    @Test
    fun auditSnapshotsKeepTheirRuleRevisionAfterUpdatesAndReset() {
        val service = service()
        val id =
            service.set(
                "builtin",
                "time.now",
                ToolApprovalPreferenceScope.SESSION,
                "s1",
                ToolApprovalPreference.ASK,
                null,
                1000,
            )
        val presented = service.snapshotFor("builtin", "time.now", "h1", "s1", null)
        service.set(
            "builtin",
            "time.now",
            ToolApprovalPreferenceScope.SESSION,
            "s1",
            ToolApprovalPreference.DENY,
            null,
            2000,
        )
        val final = service.snapshotFor("builtin", "time.now", "h1", "s1", null)
        service.remove("builtin", "time.now", ToolApprovalPreferenceScope.SESSION, "s1")
        val reset = service.snapshotFor("builtin", "time.now", "h1", "s1", null)
        assertEquals(id, presented.records.single().id)
        assertEquals(1L, presented.records.single().revision)
        assertEquals("s1", presented.records.single().scopeRef)
        assertEquals(EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT), presented.effective)
        assertEquals(id, final.records.single().id)
        assertEquals(2L, final.records.single().revision)
        assertEquals(EffectiveToolPreference.Deny, final.effective)
        assertEquals(EffectiveToolPreference.Unset, reset.effective)
        assertEquals(emptyList<com.helix.core.policy.ToolApprovalPreferenceRecord>(), reset.records)
    }

    // In-memory fake of the Room DAO: the unique (source, tool, scopeKind, scopeRef) key, the
    // rowid-ordered byTool and the delete-count are the three facts the service's paths rely on.
    private class InMemoryPreferenceDao : ToolApprovalPreferenceDao {
        private val rows = LinkedHashMap<String, ToolApprovalPreferenceEntity>()

        private fun key(e: ToolApprovalPreferenceEntity) = "${e.sourceRef}|${e.toolName}|${e.scopeKind}|${e.scopeRef}"

        override fun insert(entity: ToolApprovalPreferenceEntity) {
            check(key(entity) !in rows) { "ABORT: duplicate preference row" }
            rows[key(entity)] = entity
        }

        override fun update(
            id: String,
            preference: String,
            contractHash: String,
            revision: Long,
            updatedAtEpoch: Long,
        ) {
            val existing = rows.values.first { it.id == id }
            rows[key(existing)] =
                existing.copy(
                    preference = preference,
                    contractHash = contractHash,
                    revision = revision,
                    updatedAtEpoch = updatedAtEpoch,
                )
        }

        override fun byKey(
            sourceRef: String,
            toolName: String,
            scopeKind: String,
            scopeRef: String,
        ): ToolApprovalPreferenceEntity? =
            rows.values.firstOrNull {
                it.sourceRef == sourceRef &&
                    it.toolName == toolName &&
                    it.scopeKind == scopeKind &&
                    it.scopeRef == scopeRef
            }

        override fun byTool(
            sourceRef: String,
            toolName: String,
        ): List<ToolApprovalPreferenceEntity> =
            rows.values
                .filter { it.sourceRef == sourceRef && it.toolName == toolName }
                .sortedBy { it.scopeKind } // stable: keeps rowid order within one scopeKind

        override fun countByTool(
            sourceRef: String,
            toolName: String,
        ): Int = rows.values.count { it.sourceRef == sourceRef && it.toolName == toolName }

        override fun deleteByScope(
            sourceRef: String,
            toolName: String,
            scopeKind: String,
            scopeRef: String,
        ): Int {
            val match =
                rows.entries.firstOrNull {
                    it.value.sourceRef == sourceRef &&
                        it.value.toolName == toolName &&
                        it.value.scopeKind == scopeKind &&
                        it.value.scopeRef == scopeRef
                }
            return if (match != null) {
                rows.remove(match.key)
                1
            } else {
                0
            }
        }
    }

    // In-memory fakes of the trusted-baseline DAOs: first-write-wins insertIgnore (an existing
    // marker/anchor is never re-stamped) plus the bounded reads the resolver's new-tool decision
    // consumes. This mirrors the Room OnConflictStrategy.IGNORE the real DAOs use.
    private class InMemoryBaselineDao : ToolRegistrationBaselineDao {
        private val rows = LinkedHashMap<String, ToolRegistrationBaselineEntity>()

        private fun key(e: ToolRegistrationBaselineEntity) = "${e.sourceRef}|${e.toolName}"

        override fun insertIgnore(entity: ToolRegistrationBaselineEntity) {
            rows.putIfAbsent(key(entity), entity)
        }

        override fun firstSeenVersionCode(
            sourceRef: String,
            toolName: String,
        ): Long? =
            rows.values
                .firstOrNull { it.sourceRef == sourceRef && it.toolName == toolName }
                ?.firstSeenVersionCode
    }

    private class InMemoryMetaDao : ToolBaselineMetaDao {
        private val rows = LinkedHashMap<String, ToolBaselineMetaEntity>()

        override fun insertIgnore(entity: ToolBaselineMetaEntity) {
            rows.putIfAbsent(entity.id, entity)
        }

        override fun byId(id: String): ToolBaselineMetaEntity? = rows[id]
    }

    /**
     * A service over fresh in-memory DAOs. [currentVersionCode] defaults to 2; with the baseline
     * unseeded every tool stays OLD (founding is set to current on the first reconcile, or null
     * before one), so the existing scope/contract tests see no NEW_DEFAULT — the Gap 2 tests below
     * seed the baseline explicitly to exercise the upgrade/aging paths.
     */
    private fun service(currentVersionCode: Long = 2L) =
        ToolApprovalPreferenceService(
            ToolApprovalPreferenceRepository(InMemoryPreferenceDao()),
            ToolRegistrationBaselineRepository(InMemoryBaselineDao(), InMemoryMetaDao()),
            currentVersionCode,
        )

    @Test
    fun setThenReadBackCollapsesThroughTheSharedResolver() {
        val service = service()
        service.set(
            "builtin",
            "time.now",
            ToolApprovalPreferenceScope.GLOBAL,
            "",
            ToolApprovalPreference.ALLOW,
            "h1",
            1000L,
        )
        assertEquals(
            EffectiveToolPreference.Allow,
            service.effectiveFor("builtin", "time.now", "h1", "s1", null),
        )
    }

    @Test
    fun preferenceWritesSerializeWithExecutionStart() {
        val service = service()
        val entered = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(1)
        val writer =
            Thread {
                entered.countDown()
                service.set(
                    "builtin",
                    "time.now",
                    ToolApprovalPreferenceScope.GLOBAL,
                    "",
                    ToolApprovalPreference.DENY,
                    null,
                    1000L,
                )
                done.countDown()
            }
        try {
            service.withExecutionStart {
                writer.start()
                org.junit.Assert.assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val deadline =
                    System.nanoTime() +
                        java.util.concurrent.TimeUnit.SECONDS
                            .toNanos(5)
                while (writer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, writer.state)
                assertEquals(
                    EffectiveToolPreference.Unset,
                    service.effectiveFor("builtin", "time.now", "h1", "s1", null),
                )
            }
            org.junit.Assert.assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS))
            service.withExecutionStart {
                assertEquals(
                    EffectiveToolPreference.Deny,
                    service.effectiveFor("builtin", "time.now", "h1", "s1", null),
                )
            }
        } finally {
            writer.join(5000)
        }
    }

    @Test
    fun anAllowWithoutAContractHashFailsClosed() {
        // An ALLOW is bound to the contract it was granted for (point 6). A contract-less ALLOW could
        // never take effect (the resolver drops it at read time), so the write path rejects it instead
        // of persisting a row that silently never authorizes a call.
        val service = service()
        assertThrows(IllegalArgumentException::class.java) {
            service.set("builtin", "t", ToolApprovalPreferenceScope.GLOBAL, "", ToolApprovalPreference.ALLOW, null, 1L)
        }
    }

    @Test
    fun allowIsInvalidatedWhenTheContractChangesButDenySurvives() {
        val service = service()
        service.set(
            "builtin",
            "files.write",
            ToolApprovalPreferenceScope.GLOBAL,
            "",
            ToolApprovalPreference.ALLOW,
            "h1",
            1000L,
        )
        // Same contract: the ALLOW is live.
        assertEquals(EffectiveToolPreference.Allow, service.effectiveFor("builtin", "files.write", "h1", "s1", null))
        // Contract changed: the stored ALLOW falls back to an ASK tagged ALLOW_INVALIDATED — the
        // clarification keeps this distinct from a fresh Unset so the UI can say "your ALLOW lapsed".
        assertEquals(
            EffectiveToolPreference.Ask(ToolApprovalReason.ALLOW_INVALIDATED),
            service.effectiveFor("builtin", "files.write", "h2", "s1", null),
        )
        // A DENY carries no contract binding, so it stays live across a contract change.
        service.set(
            "builtin",
            "files.write",
            ToolApprovalPreferenceScope.GLOBAL,
            "",
            ToolApprovalPreference.DENY,
            null,
            2000L,
        )
        assertEquals(
            EffectiveToolPreference.Deny,
            service.effectiveFor("builtin", "files.write", "anything", "s1", null),
        )
    }

    @Test
    fun aNarrowerAllowCannotOverrideAnOuterDeny() {
        val service = service()
        service.set("builtin", "t", ToolApprovalPreferenceScope.GLOBAL, "", ToolApprovalPreference.DENY, null, 1L)
        service.set("builtin", "t", ToolApprovalPreferenceScope.SESSION, "s1", ToolApprovalPreference.ALLOW, "h", 2L)
        assertEquals(EffectiveToolPreference.Deny, service.effectiveFor("builtin", "t", "h", "s1", null))
    }

    @Test
    fun aNarrowerPreferenceOtherwiseWins() {
        val service = service()
        service.set("builtin", "t", ToolApprovalPreferenceScope.GLOBAL, "", ToolApprovalPreference.ALLOW, "h", 1L)
        service.set("builtin", "t", ToolApprovalPreferenceScope.SESSION, "s1", ToolApprovalPreference.DENY, null, 2L)
        assertEquals(EffectiveToolPreference.Deny, service.effectiveFor("builtin", "t", "h", "s1", null))
        // Outside that session the GLOBAL ALLOW is what applies.
        assertEquals(EffectiveToolPreference.Allow, service.effectiveFor("builtin", "t", "h", "s2", null))
    }

    @Test
    fun aSessionPreferenceIsNotLeakedAcrossSessions() {
        val service = service()
        service.set("builtin", "t", ToolApprovalPreferenceScope.SESSION, "s1", ToolApprovalPreference.ALLOW, "h", 1L)
        assertEquals(EffectiveToolPreference.Allow, service.effectiveFor("builtin", "t", "h", "s1", null))
        // No record is applicable to a different session: it is Unset (the session row never leaks).
        assertEquals(EffectiveToolPreference.Unset, service.effectiveFor("builtin", "t", "h", "s2", null))
    }

    // Gap 3 (范围不匹配 + 外部来源同名碰撞): a stored row applies ONLY to the exact scope it was
    // granted in and ONLY to the tool SOURCE it was granted for — never a looser name match. The
    // ALLOW-contract-invalidation half of Gap 3 is pinned by allowIsInvalidatedWhenTheContractChanges
    // and the device's anInvalidatedAllowFallsBackToACard.

    @Test
    fun aWorkspaceAllowIsNotLeakedAcrossWorkspaces() {
        val service = service()
        service.set("builtin", "t", ToolApprovalPreferenceScope.WORKSPACE, "w1", ToolApprovalPreference.ALLOW, "h", 1L)
        // Inside the workspace it was granted for: live.
        assertEquals(EffectiveToolPreference.Allow, service.effectiveFor("builtin", "t", "h", "s1", "w1"))
        // A different workspace — and a context with no workspace at all — never sees it: Unset.
        assertEquals(EffectiveToolPreference.Unset, service.effectiveFor("builtin", "t", "h", "s1", "w2"))
        assertEquals(EffectiveToolPreference.Unset, service.effectiveFor("builtin", "t", "h", "s1", null))
    }

    @Test
    fun aSessionAllowDoesNotApplyOutsideASessionContext() {
        // A session row can only match a call that HAS that session; a context-less call (null
        // sessionId) must not inherit it — Unset, never Allow.
        val service = service()
        service.set("builtin", "t", ToolApprovalPreferenceScope.SESSION, "s1", ToolApprovalPreference.ALLOW, "h", 1L)
        assertEquals(EffectiveToolPreference.Unset, service.effectiveFor("builtin", "t", "h", null, null))
    }

    @Test
    fun aPreferenceIsScopedToItsToolSourceNotJustTheName() {
        // External-source same-name collision: an MCP server may expose a tool with the SAME name
        // as a built-in. Identity is (sourceRef, toolName), never the bare name — an ALLOW on the
        // built-in never authorizes the external same-named tool, and a DENY on one source never
        // removes the other from the model's exposure.
        val service = service()
        service.set(
            "builtin",
            "web.search",
            ToolApprovalPreferenceScope.GLOBAL,
            "",
            ToolApprovalPreference.ALLOW,
            "h",
            1L,
        )
        assertEquals(EffectiveToolPreference.Allow, service.effectiveFor("builtin", "web.search", "h", "s1", null))
        assertEquals(EffectiveToolPreference.Unset, service.effectiveFor("mcp:other", "web.search", "h", "s1", null))
        service.set(
            "builtin",
            "web.search",
            ToolApprovalPreferenceScope.GLOBAL,
            "",
            ToolApprovalPreference.DENY,
            null,
            2L,
        )
        assertEquals(
            ToolApprovalExposure.HIDDEN_BY_DENY,
            ToolApprovalResolver.exposure(service.effectiveFor("builtin", "web.search", null, "s1", null)),
        )
        assertEquals(
            ToolApprovalExposure.EXPOSE,
            ToolApprovalResolver.exposure(service.effectiveFor("mcp:other", "web.search", null, "s1", null)),
        )
    }

    @Test
    fun removeResetsToUnsetAndFailsClosedWhenAbsent() {
        val service = service()
        service.set("builtin", "t", ToolApprovalPreferenceScope.GLOBAL, "", ToolApprovalPreference.ASK, null, 1L)
        service.remove("builtin", "t", ToolApprovalPreferenceScope.GLOBAL, "")
        assertEquals(EffectiveToolPreference.Unset, service.effectiveFor("builtin", "t", null, "s1", null))
        assertThrows(IllegalArgumentException::class.java) {
            service.remove("builtin", "t", ToolApprovalPreferenceScope.GLOBAL, "")
        }
    }

    @Test
    fun scopeValidationFailsClosed() {
        val service = service()
        assertThrows(IllegalArgumentException::class.java) {
            service.set(
                "builtin",
                "t",
                ToolApprovalPreferenceScope.GLOBAL,
                "not-empty",
                ToolApprovalPreference.ASK,
                null,
                1L,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.set("builtin", "t", ToolApprovalPreferenceScope.SESSION, "  ", ToolApprovalPreference.ASK, null, 1L)
        }
    }

    @Test
    fun aStoredDenyIsExposedAsHiddenByDeny() {
        // The read seam is the SAME source the Registry exposure filter consumes (ADR-0052 point 7).
        val service = service()
        service.set("builtin", "t", ToolApprovalPreferenceScope.GLOBAL, "", ToolApprovalPreference.DENY, null, 1L)
        assertEquals(
            ToolApprovalExposure.HIDDEN_BY_DENY,
            ToolApprovalResolver.exposure(service.effectiveFor("builtin", "t", null, "s1", null)),
        )
    }

    @Test
    fun anExplicitAskIsExposedAndCarriesTheExplicitReason() {
        // ASK is a runtime restriction (a card), not a removal: the tool stays exposed to the model
        // and the provenance is EXPLICIT, so the card text can distinguish it from a lapsed ALLOW.
        val service = service()
        service.set("builtin", "t", ToolApprovalPreferenceScope.GLOBAL, "", ToolApprovalPreference.ASK, null, 1L)
        val effective = service.effectiveFor("builtin", "t", null, "s1", null)
        assertEquals(EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT), effective)
        assertEquals(ToolApprovalExposure.EXPOSE, ToolApprovalResolver.exposure(effective))
    }

    // Gap 2 (point 1): the trusted registration/upgrade baseline drives the NEW_DEFAULT default.
    // These JVM tests exercise the service's fold of the pure ToolBaseline decision + the "never
    // configured" guard over the in-memory stores; the REAL end-to-end baseline (real Room, a real
    // app upgrade, restart) is the device test's job (HXA-200 P3).

    @Test
    fun aFreshInstallRegistersEveryBundledToolAsOldNotNew() {
        // First trusted reconcile on a fresh database: founding == current, so every bundled tool
        // first seen now is part of the founding baseline — OLD. A fresh install never forces ASK on
        // its own tools (point 1 "既有工具 UNSET"); the read seam stays Unset for an unconfigured one.
        val service = service(currentVersionCode = 2L)
        service.reconcile(
            listOf(
                ToolBaselineIdentity("builtin", "a.tool"),
                ToolBaselineIdentity("builtin", "b.tool"),
            ),
            1000L,
        )
        assertEquals(
            EffectiveToolPreference.Unset,
            service.effectiveFor("builtin", "a.tool", "h1", "s1", null),
        )
        assertEquals(
            EffectiveToolPreference.Unset,
            service.effectiveFor("builtin", "b.tool", "h1", "s1", null),
        )
    }

    /**
     * A service at current build 2 over a pre-seeded founding-build-1 baseline (old.tool
     * firstSeen=1), after the trusted path has registered the build-2 set (which adds new.tool).
     * The first-write-wins stores are real in-memory fakes, so every read hits them.
     */
    private fun upgradeService(): ToolApprovalPreferenceService {
        val prefDao = InMemoryPreferenceDao()
        val baselineDao = InMemoryBaselineDao()
        val metaDao = InMemoryMetaDao()
        metaDao.insertIgnore(ToolBaselineMetaEntity(ToolBaselineMetaEntity.BASELINE_ROW_ID, 1L, 0L))
        baselineDao.insertIgnore(
            ToolRegistrationBaselineEntity(
                sourceRef = "builtin",
                toolName = "old.tool",
                firstSeenVersionCode = 1L,
                updatedAtEpoch = 0L,
            ),
        )
        val service =
            ToolApprovalPreferenceService(
                ToolApprovalPreferenceRepository(prefDao),
                ToolRegistrationBaselineRepository(baselineDao, metaDao),
                2L,
            )
        service.reconcile(
            listOf(
                ToolBaselineIdentity("builtin", "old.tool"),
                ToolBaselineIdentity("builtin", "new.tool"),
            ),
            1000L,
        )
        return service
    }

    @Test
    fun aToolIntroducedByAnUpgradeDefaultsToNewDefaultUntilConfigured() {
        val service = upgradeService()
        // The upgrade-introduced, unconfigured tool resolves to an ASK tagged NEW_DEFAULT...
        val effective = service.effectiveFor("builtin", "new.tool", "h1", "s1", null)
        assertEquals(EffectiveToolPreference.Ask(ToolApprovalReason.NEW_DEFAULT), effective)
        // ...it is stable across a "restart" (the decision depends only on the persisted facts)...
        assertEquals(
            EffectiveToolPreference.Ask(ToolApprovalReason.NEW_DEFAULT),
            service.effectiveFor("builtin", "new.tool", "h1", "s1", null),
        )
        // ...and it is an ASK (a card), not a DENY, so the tool stays exposed to the model (point 4).
        assertEquals(ToolApprovalExposure.EXPOSE, ToolApprovalResolver.exposure(effective))
        // The founding tool is OLD and unconfigured: it keeps the original Unset handling, not NEW_DEFAULT.
        assertEquals(
            EffectiveToolPreference.Unset,
            service.effectiveFor("builtin", "old.tool", "h1", "s1", null),
        )
    }

    @Test
    fun aStoredChoiceOverridesTheNewToolDefault() {
        val service = upgradeService()
        // A real user choice overrides the new-tool default: an explicit ASK keeps the EXPLICIT tag...
        service.set(
            "builtin",
            "new.tool",
            ToolApprovalPreferenceScope.GLOBAL,
            "",
            ToolApprovalPreference.ASK,
            null,
            2000L,
        )
        assertEquals(
            EffectiveToolPreference.Ask(ToolApprovalReason.EXPLICIT),
            service.effectiveFor("builtin", "new.tool", "h1", "s1", null),
        )
        // ...and a live ALLOW makes it card-free (configured → never the new-tool default).
        service.set(
            "builtin",
            "new.tool",
            ToolApprovalPreferenceScope.GLOBAL,
            "",
            ToolApprovalPreference.ALLOW,
            "h1",
            3000L,
        )
        assertEquals(
            EffectiveToolPreference.Allow,
            service.effectiveFor("builtin", "new.tool", "h1", "s1", null),
        )
    }

    @Test
    fun aToolIntroducedInAnEarlierUpgradeBuildHasAgedOutOfNewness() {
        // Founding build 1; "aged.tool" was introduced at build 2 (firstSeen=2). The app is now build 3.
        val prefDao = InMemoryPreferenceDao()
        val baselineDao = InMemoryBaselineDao()
        val metaDao = InMemoryMetaDao()
        metaDao.insertIgnore(ToolBaselineMetaEntity(ToolBaselineMetaEntity.BASELINE_ROW_ID, 1L, 0L))
        baselineDao.insertIgnore(
            ToolRegistrationBaselineEntity(
                sourceRef = "builtin",
                toolName = "aged.tool",
                firstSeenVersionCode = 2L,
                updatedAtEpoch = 0L,
            ),
        )
        val service =
            ToolApprovalPreferenceService(
                ToolApprovalPreferenceRepository(prefDao),
                ToolRegistrationBaselineRepository(baselineDao, metaDao),
                3L,
            )
        // isNewDefault(3, 1, 2) is false: the tool is no longer "new in the current build," so an
        // unconfigured tool resolves Unset (normal handling), NOT NEW_DEFAULT.
        assertEquals(
            EffectiveToolPreference.Unset,
            service.effectiveFor("builtin", "aged.tool", "h1", "s1", null),
        )
    }
}
