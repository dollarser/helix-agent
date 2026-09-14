package com.helix.app.approval

import com.helix.core.model.ToolApprovalPreference
import com.helix.core.model.ToolApprovalPreferenceScope
import com.helix.core.policy.ToolApprovalExposure
import com.helix.core.policy.ToolApprovalResolver
import com.helix.core.storage.dao.ToolApprovalPreferenceDao
import com.helix.core.storage.entity.ToolApprovalPreferenceEntity
import com.helix.core.storage.repository.ToolApprovalPreferenceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The user tool-approval preference service (HXA-200, ADR-0052): the ONLY write path and the live
 * read seam the Dispatcher and Registry exposure filter share. This JVM test exercises the service
 * against an in-memory DAO so the set/remove/applicable threading and the shared-resolver collapse
 * are proven without a device; the REAL end-to-end behavior (real Room, Registry exposure,
 * dispatcher pre-start re-resolution, restart/migration) is the device test's job, not this one.
 */
class ToolApprovalPreferenceServiceTest {
    // In-memory fake of the Room DAO: the unique (source, tool, scopeKind, scopeRef) key, the
    // rowid-ordered byTool and the delete-count are the three facts the service's paths rely on.
    private class InMemoryPreferenceDao : ToolApprovalPreferenceDao {
        private val rows = LinkedHashMap<String, ToolApprovalPreferenceEntity>()

        private fun key(e: ToolApprovalPreferenceEntity): String =
            "${e.sourceRef}|${e.toolName}|${e.scopeKind}|${e.scopeRef}"

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

    private fun service() = ToolApprovalPreferenceService(ToolApprovalPreferenceRepository(InMemoryPreferenceDao()))

    @Test fun setThenReadBackCollapsesThroughTheSharedResolver() {
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
            ToolApprovalPreference.ALLOW,
            service.effectiveFor("builtin", "time.now", "h1", "s1", null),
        )
    }

    @Test fun anAllowWithoutAContractHashFailsClosed() {
        // An ALLOW is bound to the contract it was granted for (point 6). A contract-less ALLOW could
        // never take effect (the resolver drops it at read time), so the write path rejects it instead
        // of persisting a row that silently never authorizes a call.
        val service = service()
        assertThrows(IllegalArgumentException::class.java) {
            service.set("builtin", "t", ToolApprovalPreferenceScope.GLOBAL, "", ToolApprovalPreference.ALLOW, null, 1L)
        }
    }

    @Test fun allowIsInvalidatedWhenTheContractChangesButDenySurvives() {
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
        assertEquals(ToolApprovalPreference.ALLOW, service.effectiveFor("builtin", "files.write", "h1", "s1", null))
        // Contract changed: the stored ALLOW falls back to unset (the ASK default lives in the resolver).
        assertNull(service.effectiveFor("builtin", "files.write", "h2", "s1", null))
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
            ToolApprovalPreference.DENY,
            service.effectiveFor("builtin", "files.write", "anything", "s1", null),
        )
    }

    @Test fun aNarrowerAllowCannotOverrideAnOuterDeny() {
        val service = service()
        service.set("builtin", "t", ToolApprovalPreferenceScope.GLOBAL, "", ToolApprovalPreference.DENY, null, 1L)
        service.set("builtin", "t", ToolApprovalPreferenceScope.SESSION, "s1", ToolApprovalPreference.ALLOW, "h", 2L)
        assertEquals(ToolApprovalPreference.DENY, service.effectiveFor("builtin", "t", "h", "s1", null))
    }

    @Test fun aNarrowerPreferenceOtherwiseWins() {
        val service = service()
        service.set("builtin", "t", ToolApprovalPreferenceScope.GLOBAL, "", ToolApprovalPreference.ALLOW, "h", 1L)
        service.set("builtin", "t", ToolApprovalPreferenceScope.SESSION, "s1", ToolApprovalPreference.DENY, null, 2L)
        assertEquals(ToolApprovalPreference.DENY, service.effectiveFor("builtin", "t", "h", "s1", null))
        // Outside that session the GLOBAL ALLOW is what applies.
        assertEquals(ToolApprovalPreference.ALLOW, service.effectiveFor("builtin", "t", "h", "s2", null))
    }

    @Test fun aSessionPreferenceIsNotLeakedAcrossSessions() {
        val service = service()
        service.set("builtin", "t", ToolApprovalPreferenceScope.SESSION, "s1", ToolApprovalPreference.ALLOW, "h", 1L)
        assertEquals(ToolApprovalPreference.ALLOW, service.effectiveFor("builtin", "t", "h", "s1", null))
        assertNull(service.effectiveFor("builtin", "t", "h", "s2", null))
    }

    @Test fun removeResetsToUnsetAndFailsClosedWhenAbsent() {
        val service = service()
        service.set("builtin", "t", ToolApprovalPreferenceScope.GLOBAL, "", ToolApprovalPreference.ASK, null, 1L)
        service.remove("builtin", "t", ToolApprovalPreferenceScope.GLOBAL, "")
        assertNull(service.effectiveFor("builtin", "t", null, "s1", null))
        assertThrows(IllegalArgumentException::class.java) {
            service.remove("builtin", "t", ToolApprovalPreferenceScope.GLOBAL, "")
        }
    }

    @Test fun scopeValidationFailsClosed() {
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

    @Test fun aStoredDenyIsExposedAsHiddenByDeny() {
        // The read seam is the SAME source the Registry exposure filter consumes (ADR-0052 point 7).
        val service = service()
        service.set("builtin", "t", ToolApprovalPreferenceScope.GLOBAL, "", ToolApprovalPreference.DENY, null, 1L)
        assertEquals(
            ToolApprovalExposure.HIDDEN_BY_DENY,
            ToolApprovalResolver.exposure(service.effectiveFor("builtin", "t", null, "s1", null)),
        )
    }
}
