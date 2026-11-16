package com.helix.core.storage.repository

import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.model.ToolAvailabilityStates
import com.helix.core.storage.assertThrows
import com.helix.core.storage.dao.ToolAvailabilityDao
import com.helix.core.storage.entity.ToolAvailabilityEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolAvailabilityRepositoryTest {
    private val dao = FakeToolAvailabilityDao()
    private val repository = ToolAvailabilityRepository(dao)

    @Test fun currentNamesAndStableSourcesKeepScopesAndSourcesSeparate() {
        val source = "plugin:mobile-use:mobile-use"
        repository.set(source, "ui_click", ToolAvailabilityScope.GLOBAL, "", ToolAvailabilityState.DISABLED, 10)
        repository.set(source, "ui_click", ToolAvailabilityScope.SESSION, "session", ToolAvailabilityState.ENABLED, 20)
        repository.set(
            source,
            "ui_click",
            ToolAvailabilityScope.WORKSPACE,
            "workspace",
            ToolAvailabilityState.DISABLED,
            30,
        )
        val states = repository.statesFor(source, "ui_click", "session", "workspace")
        assertEquals(ToolAvailabilityState.DISABLED, states.global)
        assertEquals(ToolAvailabilityState.ENABLED, states.session)
        assertEquals(ToolAvailabilityState.DISABLED, states.workspace)
        assertNull(repository.statesFor(source, "ui_click", "another-session", "another-workspace").session)
        assertNull(repository.statesFor(source, "ui_click", "another-session", "another-workspace").workspace)
        assertEquals(
            ToolAvailabilityStates(),
            repository.statesFor("plugin:other:mobile-use", "ui_click", "session", null),
        )
        repository.set(
            "built-in",
            "goal_create",
            ToolAvailabilityScope.SESSION,
            "session",
            ToolAvailabilityState.DISABLED,
            40,
        )
        assertEquals(
            ToolAvailabilityState.DISABLED,
            repository.statesFor("built-in", "goal_create", "session", null).session,
        )
        assertEquals(ToolAvailabilityStates(), repository.statesFor("mcp:other", "goal_create", "session", null))
    }

    @Test fun obsoleteKeysRemainDistinctAndAreNotMovedToCurrentIdentities() {
        val obsoleteSource = "plugin:mobile-use:0.2.0:mobile-use"
        repository.set(obsoleteSource, "ui_click", ToolAvailabilityScope.GLOBAL, "", ToolAvailabilityState.DISABLED, 10)
        repository.set("built-in", "create_goal", ToolAvailabilityScope.GLOBAL, "", ToolAvailabilityState.DISABLED, 20)
        assertEquals(
            ToolAvailabilityStates(),
            repository.statesFor("plugin:mobile-use:mobile-use", "ui_click", "session", null),
        )
        assertEquals(ToolAvailabilityStates(), repository.statesFor("built-in", "goal_create", "session", null))
        assertEquals(1, repository.byTool(obsoleteSource, "ui_click").size)
        assertEquals(1, repository.byTool("built-in", "create_goal").size)
    }

    @Test
    fun setStoresAnInitialStateAtRevisionOne() {
        val revision =
            repository.set(
                sourceRef = "local",
                toolName = "bash",
                scope = ToolAvailabilityScope.GLOBAL,
                scopeRef = "",
                state = ToolAvailabilityState.DISABLED,
                nowEpochMillis = 100L,
            )
        assertEquals(1L, revision)
        val row = dao.rows.single()
        assertEquals("DISABLED", row.state)
        assertEquals("GLOBAL", row.scopeKind)
        assertEquals(100L, row.createdAtEpoch)
        assertEquals(100L, row.updatedAtEpoch)
    }

    @Test
    fun setUpdatesAnExistingRowWithoutTouchingTheCreationTime() {
        repository.set("local", "bash", ToolAvailabilityScope.GLOBAL, "", ToolAvailabilityState.DISABLED, 100L)
        val revision =
            repository.set("local", "bash", ToolAvailabilityScope.GLOBAL, "", ToolAvailabilityState.ENABLED, 200L)
        assertEquals(2L, revision)
        val row = dao.rows.single()
        assertEquals("ENABLED", row.state)
        assertEquals(100L, row.createdAtEpoch)
        assertEquals(200L, row.updatedAtEpoch)
    }

    @Test
    fun setEnforcesTheScopeRefContract() {
        assertThrows("GLOBAL must carry an empty scopeRef") {
            repository.set("local", "bash", ToolAvailabilityScope.GLOBAL, "s-1", ToolAvailabilityState.DISABLED, 1L)
        }
        assertThrows("SESSION requires a scopeRef") {
            repository.set("local", "bash", ToolAvailabilityScope.SESSION, "", ToolAvailabilityState.DISABLED, 1L)
        }
        assertThrows("WORKSPACE requires a scopeRef") {
            repository.set("local", "bash", ToolAvailabilityScope.WORKSPACE, " ", ToolAvailabilityState.DISABLED, 1L)
        }
        assertEquals(0, dao.rows.size)
    }

    @Test
    fun removeDeletesTheRowAndOnlyOnce() {
        repository.set("local", "bash", ToolAvailabilityScope.SESSION, "s-1", ToolAvailabilityState.DISABLED, 100L)
        repository.remove("local", "bash", ToolAvailabilityScope.SESSION, "s-1")
        assertEquals(0, dao.rows.size)
        assertThrows("a second remove has no row to delete") {
            repository.remove("local", "bash", ToolAvailabilityScope.SESSION, "s-1")
        }
    }

    @Test
    fun statesForReturnsNullSlotsWithoutAnyStoredState() {
        val states = repository.statesFor("local", "bash", sessionId = "s-1", workspaceRef = "w-1")
        assertEquals(ToolAvailabilityStates(), states)
    }

    @Test
    fun statesForPicksTheRowMatchingTheContextOnly() {
        repository.set("local", "bash", ToolAvailabilityScope.GLOBAL, "", ToolAvailabilityState.DISABLED, 1L)
        repository.set("local", "bash", ToolAvailabilityScope.WORKSPACE, "w-1", ToolAvailabilityState.ENABLED, 2L)
        repository.set("local", "bash", ToolAvailabilityScope.SESSION, "s-1", ToolAvailabilityState.ENABLED, 3L)

        val inContext = repository.statesFor("local", "bash", sessionId = "s-1", workspaceRef = "w-1")
        assertEquals(ToolAvailabilityState.DISABLED, inContext.global)
        assertEquals(ToolAvailabilityState.ENABLED, inContext.workspace)
        assertEquals(ToolAvailabilityState.ENABLED, inContext.session)

        val otherSession = repository.statesFor("local", "bash", sessionId = "s-2", workspaceRef = "w-1")
        assertEquals(ToolAvailabilityState.DISABLED, otherSession.global)
        assertEquals(ToolAvailabilityState.ENABLED, otherSession.workspace)
        assertNull(otherSession.session)

        val noContext = repository.statesFor("local", "bash", sessionId = null, workspaceRef = null)
        assertEquals(ToolAvailabilityState.DISABLED, noContext.global)
        assertNull(noContext.workspace)
        assertNull(noContext.session)
    }

    @Test
    fun byToolListsOnlyTheRowsOfOneIdentity() {
        repository.set("local", "bash", ToolAvailabilityScope.GLOBAL, "", ToolAvailabilityState.DISABLED, 1L)
        repository.set("remote", "bash", ToolAvailabilityScope.GLOBAL, "", ToolAvailabilityState.ENABLED, 2L)
        assertEquals(1, repository.byTool("local", "bash").size)
        assertEquals("local", repository.byTool("local", "bash").single().sourceRef)
    }
}

private class FakeToolAvailabilityDao : ToolAvailabilityDao {
    val rows = mutableListOf<ToolAvailabilityEntity>()

    private fun isSame(
        a: ToolAvailabilityEntity,
        sourceRef: String,
        toolName: String,
        scopeKind: String,
        scopeRef: String,
    ) = a.sourceRef == sourceRef &&
        a.toolName == toolName &&
        a.scopeKind == scopeKind &&
        a.scopeRef == scopeRef

    override fun insert(entity: ToolAvailabilityEntity) {
        val index =
            rows.indexOfFirst {
                isSame(it, entity.sourceRef, entity.toolName, entity.scopeKind, entity.scopeRef)
            }
        if (index >= 0) {
            rows[index] = entity
        } else {
            rows += entity
        }
    }

    override fun byKey(
        sourceRef: String,
        toolName: String,
        scopeKind: String,
        scopeRef: String,
    ): ToolAvailabilityEntity? = rows.firstOrNull { isSame(it, sourceRef, toolName, scopeKind, scopeRef) }

    override fun byTool(
        sourceRef: String,
        toolName: String,
    ): List<ToolAvailabilityEntity> = rows.filter { it.sourceRef == sourceRef && it.toolName == toolName }

    override fun deleteByKey(
        sourceRef: String,
        toolName: String,
        scopeKind: String,
        scopeRef: String,
    ): Int {
        val index = rows.indexOfFirst { isSame(it, sourceRef, toolName, scopeKind, scopeRef) }
        if (index < 0) return 0
        rows.removeAt(index)
        return 1
    }
}
