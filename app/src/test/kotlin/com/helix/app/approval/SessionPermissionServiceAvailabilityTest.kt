package com.helix.app.approval

import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.policy.effectiveAvailability
import com.helix.core.storage.dao.SessionPermissionConfigDao
import com.helix.core.storage.dao.SessionPermissionDefaultsDao
import com.helix.core.storage.dao.ToolAvailabilityDao
import com.helix.core.storage.entity.SessionPermissionConfigEntity
import com.helix.core.storage.entity.SessionPermissionDefaultsEntity
import com.helix.core.storage.entity.ToolAvailabilityEntity
import com.helix.core.storage.repository.SessionPermissionConfigRepository
import com.helix.core.storage.repository.ToolAvailabilityRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * HXA-209 C1/C2: the ONE availability read — the dispatcher's execution entry and the
 * session-scoped exposure surfaces anchor the WORKSPACE slot to the SAME trusted session
 * workspace, a disable never bleeds across tool identities or sessions, and re-enabling
 * (a row delete) restores ENABLED — the two-state model has no ASK to restore.
 *
 * The REAL B2 repositories run here over in-memory DAO fakes: the contract under test is the
 * app adapter's workspace anchoring on top of the storage fold, not Room itself.
 */
class SessionPermissionServiceAvailabilityTest {
    @Test
    fun aWorkspaceDisableIsEnforcedEvenWhenTheCallCarriesNoWorkspaceScope() {
        // The dispatcher's live read passes the CALL's scope (null for nearly every tool),
        // not the session's workspace — the service must anchor the workspace row itself, or
        // a WORKSPACE-scope disable is enforced on the exposure surfaces but missed at the
        // execution entry (and vice versa).
        val fx = Fixture()
        fx.workspaces["s1"] = "w1"
        fx.disableAt("built-in", "fs.write", ToolAvailabilityScope.WORKSPACE, "w1")
        assertEquals(
            ToolAvailabilityState.DISABLED,
            fx.effectiveState("built-in", "fs.write", "s1", workspaceRef = null),
        )
        // and the exposure side (claiming the session's workspace) sees the same state
        assertEquals(
            ToolAvailabilityState.DISABLED,
            fx.effectiveState("built-in", "fs.write", "s1", "w1"),
        )
    }

    @Test
    fun theSessionWorkspaceAnchorsTheReadNotAClaimedDifferentScope() {
        val fx = Fixture()
        fx.workspaces["s1"] = "w1"
        fx.disableAt("built-in", "fs.write", ToolAvailabilityScope.WORKSPACE, "w1")
        // a call asserting another trusted workspace still hits the session's stored disable
        assertEquals(
            ToolAvailabilityState.DISABLED,
            fx.effectiveState("built-in", "fs.write", "s1", "w2"),
        )
    }

    @Test
    fun aDisableInTheCallAssertedWorkspaceAlsoStopsTheExecutionEntry() {
        val fx = Fixture()
        fx.workspaces["s1"] = "w1"
        fx.disableAt("built-in", "fs.write", ToolAvailabilityScope.WORKSPACE, "w2")
        // exposure (session-scoped) is unaffected; the execution entry must be at least
        // as strict — a disable in the workspace the call actually asserts wins
        assertEquals(
            ToolAvailabilityState.ENABLED,
            fx.effectiveState("built-in", "fs.write", "s1", "w1"),
        )
        assertEquals(
            ToolAvailabilityState.DISABLED,
            fx.effectiveState("built-in", "fs.write", "s1", "w2"),
        )
    }

    @Test
    fun aDisableNeverBleedsAcrossToolIdentities() {
        val fx = Fixture()
        fx.workspaces["s1"] = "w1"
        fx.disableAt("built-in", "fs.write", ToolAvailabilityScope.WORKSPACE, "w1")
        // different tool, same source
        assertEquals(ToolAvailabilityState.ENABLED, fx.effectiveState("built-in", "fs.read", "s1", null))
        // same name under a different source (the name namespaces prevent this in production;
        // the store must still key on the full identity, never the name alone)
        assertEquals(
            ToolAvailabilityState.ENABLED,
            fx.effectiveState("mcp:srv:1:hash", "fs.write", "s1", null),
        )
    }

    @Test
    fun aSessionScopedDisableDoesNotBleedToOtherSessions() {
        val fx = Fixture()
        fx.workspaces["s1"] = "w1"
        fx.workspaces["s2"] = "w1"
        fx.disableAt("built-in", "fs.write", ToolAvailabilityScope.SESSION, "s1")
        assertEquals(ToolAvailabilityState.DISABLED, fx.effectiveState("built-in", "fs.write", "s1", null))
        assertEquals(ToolAvailabilityState.ENABLED, fx.effectiveState("built-in", "fs.write", "s2", null))
    }

    @Test
    fun reEnablingIsARowDeleteAndRestoresEnabledNeverAsk() {
        val fx = Fixture()
        fx.workspaces["s1"] = "w1"
        fx.disableAt("built-in", "fs.write", ToolAvailabilityScope.WORKSPACE, "w1")
        assertEquals(ToolAvailabilityState.DISABLED, fx.effectiveState("built-in", "fs.write", "s1", null))
        fx.availability.remove("built-in", "fs.write", ToolAvailabilityScope.WORKSPACE, "w1")
        // the slot is EMPTY again — not an explicit ENABLED, and there is no ASK to return to
        assertNull(fx.service.statesFor("built-in", "fs.write", "s1", null).workspace)
        assertEquals(ToolAvailabilityState.ENABLED, fx.effectiveState("built-in", "fs.write", "s1", null))
        assertEquals(
            setOf("ENABLED", "DISABLED"),
            ToolAvailabilityState.values().map { it.name }.toSet(),
        )
    }

    @Test
    fun anUnknownSessionFallsBackToTheCallWorkspace() {
        val fx = Fixture()
        fx.disableAt("built-in", "fs.write", ToolAvailabilityScope.WORKSPACE, "w9")
        // workspaceFor("ghost") is null (no session row): the caller's workspace is used,
        // the pre-C behavior for a missing session
        assertEquals(
            ToolAvailabilityState.DISABLED,
            fx.effectiveState("built-in", "fs.write", "ghost", "w9"),
        )
        assertEquals(ToolAvailabilityState.ENABLED, fx.effectiveState("built-in", "fs.write", "ghost", "w1"))
    }

    @Test
    fun configForFallsBackToTheReadonlyAppDefault() {
        val fx = Fixture()
        // no defaults row, no session row: the compiled READ_ONLY default (never a widen)
        assertEquals(SessionPermissionMode.READ_ONLY, fx.service.configFor("s1").mode)
        fx.configs.setForSession("s1", SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS), 1000L)
        assertEquals(SessionPermissionMode.FULL_ACCESS, fx.service.configFor("s1").mode)
    }

    /** The real B2 repositories over in-memory DAO fakes, plus the session-workspace seam. */
    private class Fixture {
        val workspaces = mutableMapOf<String, String>()
        val availability = ToolAvailabilityRepository(FakeToolAvailabilityDao())
        val configs =
            SessionPermissionConfigRepository(FakeSessionPermissionConfigDao(), FakeSessionPermissionDefaultsDao())
        val service = SessionPermissionService(configs, availability) { sessionId -> workspaces[sessionId] }

        fun disableAt(
            sourceRef: String,
            toolName: String,
            scope: ToolAvailabilityScope,
            scopeRef: String,
        ): Long = availability.set(sourceRef, toolName, scope, scopeRef, ToolAvailabilityState.DISABLED, 1000L)

        /** The single effective-state fold every surface must use (outer disable wins). */
        fun effectiveState(
            sourceRef: String,
            toolName: String,
            sessionId: String?,
            workspaceRef: String?,
        ): ToolAvailabilityState {
            val states = service.statesFor(sourceRef, toolName, sessionId, workspaceRef)
            return effectiveAvailability(states.global, states.workspace, states.session)
        }
    }

    private class FakeToolAvailabilityDao : ToolAvailabilityDao {
        private val rows = LinkedHashMap<String, ToolAvailabilityEntity>()

        override fun insert(entity: ToolAvailabilityEntity) {
            rows[keyOf(entity)] = entity
        }

        override fun byKey(
            sourceRef: String,
            toolName: String,
            scopeKind: String,
            scopeRef: String,
        ): ToolAvailabilityEntity? = rows[keyOf(sourceRef, toolName, scopeKind, scopeRef)]

        override fun byTool(
            sourceRef: String,
            toolName: String,
        ): List<ToolAvailabilityEntity> =
            rows.values
                .filter { it.sourceRef == sourceRef && it.toolName == toolName }
                .sortedBy { it.scopeKind }

        override fun deleteByKey(
            sourceRef: String,
            toolName: String,
            scopeKind: String,
            scopeRef: String,
        ): Int = rows.remove(keyOf(sourceRef, toolName, scopeKind, scopeRef))?.let { 1 } ?: 0

        override fun all(): List<ToolAvailabilityEntity> =
            rows.values.sortedWith(
                compareBy(
                    { it.sourceRef },
                    { it.toolName },
                    { it.scopeKind },
                    { it.scopeRef },
                ),
            )

        private fun keyOf(entity: ToolAvailabilityEntity): String =
            keyOf(entity.sourceRef, entity.toolName, entity.scopeKind, entity.scopeRef)

        private fun keyOf(
            sourceRef: String,
            toolName: String,
            scopeKind: String,
            scopeRef: String,
        ): String = listOf(sourceRef, toolName, scopeKind, scopeRef).joinToString(" ")
    }

    private class FakeSessionPermissionConfigDao : SessionPermissionConfigDao {
        private val rows = LinkedHashMap<String, SessionPermissionConfigEntity>()

        override fun insert(entity: SessionPermissionConfigEntity) {
            rows[entity.sessionId] = entity
        }

        override fun bySession(sessionId: String): SessionPermissionConfigEntity? = rows[sessionId]

        override fun deleteBySession(sessionId: String): Int = rows.remove(sessionId)?.let { 1 } ?: 0
    }

    private class FakeSessionPermissionDefaultsDao : SessionPermissionDefaultsDao {
        private val rows = LinkedHashMap<String, SessionPermissionDefaultsEntity>()

        override fun insert(entity: SessionPermissionDefaultsEntity) {
            rows[entity.id] = entity
        }

        override fun byId(id: String): SessionPermissionDefaultsEntity? = rows[id]
    }
}
