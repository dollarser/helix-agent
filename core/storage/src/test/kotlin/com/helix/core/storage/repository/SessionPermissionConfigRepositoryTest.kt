package com.helix.core.storage.repository

import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.SessionPermissionMode
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.assertThrows
import com.helix.core.storage.assertThrowsAny
import com.helix.core.storage.dao.SessionPermissionConfigDao
import com.helix.core.storage.dao.SessionPermissionDefaultsDao
import com.helix.core.storage.entity.SessionPermissionConfigEntity
import com.helix.core.storage.entity.SessionPermissionDefaultsEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionPermissionConfigRepositoryTest {
    private val configDao = FakeSessionPermissionConfigDao()
    private val defaultsDao = FakeSessionPermissionDefaultsDao()
    private val repository = SessionPermissionConfigRepository(configDao, defaultsDao)

    @Test
    fun missingSessionRowMeansNoStoredConfig() {
        assertNull(repository.forSession("session-1"))
    }

    @Test
    fun setForSessionStoresAWorkspacePresetWithItsFixedTable() {
        val revision =
            repository.setForSession(
                "session-1",
                SessionPermissionConfig.of(SessionPermissionMode.WORKSPACE),
                100L,
            )
        assertEquals(1L, revision)
        val stored = repository.forSession("session-1")
        assertEquals(SessionPermissionMode.WORKSPACE, stored?.mode)
        assertEquals(
            SessionPermissionConfig.presetRules(SessionPermissionMode.WORKSPACE),
            stored?.rules,
        )
        val row = configDao.rows.values.single()
        assertEquals(100L, row.createdAtEpoch)
        assertEquals(100L, row.updatedAtEpoch)
    }

    @Test
    fun setForSessionAdvancesTheRevisionAndKeepsTheCreationTime() {
        repository.setForSession("session-1", SessionPermissionConfig.of(SessionPermissionMode.WORKSPACE), 100L)
        val revision =
            repository.setForSession(
                "session-1",
                SessionPermissionConfig.of(SessionPermissionMode.READ_ONLY),
                200L,
            )
        assertEquals(2L, revision)
        val row = configDao.rows.values.single()
        assertEquals(100L, row.createdAtEpoch)
        assertEquals(200L, row.updatedAtEpoch)
        assertEquals(SessionPermissionMode.READ_ONLY, repository.forSession("session-1")?.mode)
    }

    @Test
    fun setForSessionAcceptsAnExplicitCustomSnapshot() {
        val snapshot =
            SessionPermissionConfig.copyPreset(SessionPermissionMode.READ_ONLY).toMutableMap().apply {
                put(OperationEffect.FILE_MUTATION_WORKSPACE, OperationRule.DENY)
            }
        repository.setForSession("session-1", SessionPermissionConfig.custom(snapshot), 100L)
        assertEquals(snapshot, repository.forSession("session-1")?.rules)
        assertEquals(SessionPermissionMode.CUSTOM, repository.forSession("session-1")?.mode)
    }

    @Test
    fun setForSessionRejectsAPresetCarryingAForeignRuleTable() {
        val foreign =
            SessionPermissionConfig(
                SessionPermissionMode.WORKSPACE,
                SessionPermissionConfig.presetRules(SessionPermissionMode.READ_ONLY),
            )
        assertThrows("a preset mode must carry its exact preset rule table") {
            repository.setForSession("session-1", foreign, 100L)
        }
        assertNull(repository.forSession("session-1"))
    }

    @Test
    fun resetToDefaultDeletesTheRowAndOnlyOnce() {
        repository.setForSession("session-1", SessionPermissionConfig.of(SessionPermissionMode.WORKSPACE), 100L)
        repository.resetToDefault("session-1")
        assertNull(repository.forSession("session-1"))
        assertThrows("a second reset has no row to delete") { repository.resetToDefault("session-1") }
    }

    @Test
    fun appDefaultWithoutARowIsTheCompiledReadOnlyDefault() {
        val appDefault = repository.appDefault()
        assertEquals(SessionPermissionMode.READ_ONLY, appDefault.mode)
        assertEquals(
            SessionPermissionConfig.presetRules(SessionPermissionMode.READ_ONLY),
            appDefault.rules,
        )
    }

    @Test
    fun appDefaultReadsTheStoredPresetRow() {
        assertEquals(1L, repository.setAppDefault(SessionPermissionMode.WORKSPACE, 10L))
        val appDefault = repository.appDefault()
        assertEquals(SessionPermissionMode.WORKSPACE, appDefault.mode)
        assertEquals(
            SessionPermissionConfig.presetRules(SessionPermissionMode.WORKSPACE),
            appDefault.rules,
        )
        assertEquals(2L, repository.setAppDefault(SessionPermissionMode.READ_ONLY, 20L))
        assertEquals(SessionPermissionMode.READ_ONLY, repository.appDefault().mode)
    }

    @Test
    fun setAppDefaultRejectsCustom() {
        assertThrows("the app default must be a preset") {
            repository.setAppDefault(SessionPermissionMode.CUSTOM, 10L)
        }
    }
}

private class FakeSessionPermissionConfigDao : SessionPermissionConfigDao {
    val rows = mutableMapOf<String, SessionPermissionConfigEntity>()

    override fun insert(entity: SessionPermissionConfigEntity) {
        rows[entity.sessionId] = entity
    }

    override fun bySession(sessionId: String): SessionPermissionConfigEntity? = rows[sessionId]

    override fun deleteBySession(sessionId: String): Int = if (rows.remove(sessionId) != null) 1 else 0
}

private class FakeSessionPermissionDefaultsDao : SessionPermissionDefaultsDao {
    val rows = mutableMapOf<String, SessionPermissionDefaultsEntity>()

    override fun insert(entity: SessionPermissionDefaultsEntity) {
        rows[entity.id] = entity
    }

    override fun byId(id: String): SessionPermissionDefaultsEntity? = rows[id]
}
