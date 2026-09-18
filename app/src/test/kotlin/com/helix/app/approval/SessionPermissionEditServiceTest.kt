package com.helix.app.approval

import com.helix.core.model.OperationEffect
import com.helix.core.model.OperationRule
import com.helix.core.model.SessionPermissionMode
import com.helix.core.model.ToolAvailabilityScope
import com.helix.core.model.ToolAvailabilityState
import com.helix.core.policy.SessionPermissionConfig
import com.helix.core.storage.dao.SessionPermissionConfigDao
import com.helix.core.storage.dao.SessionPermissionDefaultsDao
import com.helix.core.storage.dao.SessionPermissionDraftDao
import com.helix.core.storage.dao.ToolAvailabilityDao
import com.helix.core.storage.entity.SessionPermissionConfigEntity
import com.helix.core.storage.entity.SessionPermissionDefaultsEntity
import com.helix.core.storage.entity.SessionPermissionDraftEntity
import com.helix.core.storage.entity.ToolAvailabilityEntity
import com.helix.core.storage.repository.SessionPermissionConfigRepository
import com.helix.core.storage.repository.ToolAvailabilityRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * HXA-209 D1: the app's WRITE path for the session authorization — the service the settings
 * UI operates through (ADR-PERMISSIONS-001 section 5: "UI 经服务操作，不直接写 DAO"). Every
 * change produces an independent audit event (mode, rule-set version, change time, binding),
 * a preset is never stored under a wrong rule table, a CUSTOM app-default is refused, the
 * two-state tool availability has no ASK to restore, and a no-op writes nothing.
 *
 * The REAL B2 repositories run over in-memory DAO fakes, and the audit is a recording seam:
 * the contract under test is the service's write orchestration + audit shape, not Room.
 */
@Suppress("TooManyFunctions") // one test per write/audit contract
class SessionPermissionEditServiceTest {
    @Test
    fun savingAPresetStoresItAndAuditsTheModeVersionAndRevision() {
        val fx = Fixture()
        val revision =
            fx.service.saveSessionConfig(
                "s1",
                SessionPermissionConfig.of(SessionPermissionMode.WORKSPACE),
                1000L,
            )
        assertEquals(1L, revision)
        assertEquals(SessionPermissionMode.WORKSPACE, fx.configs.forSession("s1")!!.mode)
        val audit = fx.audit.single()
        assertEquals(SessionPermissionEditService.TYPE, audit.type)
        assertEquals(SessionPermissionEditService.ACTOR, audit.actor)
        assertEquals("s1", audit.correlationId)
        assertEquals("session_config", audit.action())
        assertEquals("WORKSPACE", audit.str("mode"))
        assertEquals("1", audit.str("configVersion"))
        assertEquals("1", audit.str("revision"))
        assertEquals("1000", audit.str("changedAt"))
    }

    @Test
    fun savingACustomConfigStoresItsExplicitRules() {
        val fx = Fixture()
        fx.service.saveSessionConfig(
            "s1",
            SessionPermissionConfig.custom(mapOf(OperationEffect.COMMAND_EXECUTION to OperationRule.DENY)),
            1000L,
        )
        val stored = fx.configs.forSession("s1")!!
        assertEquals(SessionPermissionMode.CUSTOM, stored.mode)
        assertEquals(OperationRule.DENY, stored.rules[OperationEffect.COMMAND_EXECUTION])
        assertEquals("CUSTOM", fx.audit.single().str("mode"))
    }

    @Test
    fun aPresetCarryingANonPresetTableIsRejectedBeforeAnyWriteOrAudit() {
        val fx = Fixture()
        val smuggled =
            SessionPermissionConfig(
                mode = SessionPermissionMode.WORKSPACE,
                rules = mapOf(OperationEffect.COMMAND_EXECUTION to OperationRule.DENY),
                configVersion = SessionPermissionConfig.CURRENT_CONFIG_VERSION,
            )
        try {
            fx.service.saveSessionConfig("s1", smuggled, 1000L)
            fail("expected the repository to reject a preset carrying a non-preset table")
        } catch (expected: IllegalArgumentException) {
            // the fail-closed path: no write, no audit
        }
        assertNull(fx.configs.forSession("s1"))
        assertTrue("a refused write must not be audited", fx.audit.isEmpty())
    }

    @Test
    fun resetToDefaultStoresSnapshotAndAuditsTheResultingDefaultMode() {
        val fx = Fixture()
        fx.service.saveSessionConfig("s1", SessionPermissionConfig.of(SessionPermissionMode.FULL_ACCESS), 1000L)
        fx.service.resetSessionToDefault("s1", 2000L)
        assertEquals(SessionPermissionMode.READ_ONLY, fx.configs.forSession("s1")?.mode)
        // the session now resolves to the (unset) app default: READ_ONLY
        val audit = fx.audit[1]
        assertEquals("reset_to_default", audit.action())
        assertEquals("READ_ONLY", audit.str("mode"))
        assertEquals("s1", audit.correlationId)
    }

    @Test
    fun settingTheNewSessionDefaultStoresItAndAudits() {
        val fx = Fixture()
        val revision = fx.service.setNewSessionDefault(SessionPermissionMode.WORKSPACE, 1000L)
        assertEquals(1L, revision)
        assertEquals(SessionPermissionMode.WORKSPACE, fx.configs.appDefault().mode)
        val audit = fx.audit.single()
        assertEquals("app_default", audit.action())
        assertEquals("WORKSPACE", audit.str("mode"))
        assertEquals(SessionPermissionEditService.TYPE, audit.type)
    }

    @Test
    fun aCustomNewSessionDefaultIsRefusedAndNotAudited() {
        val fx = Fixture()
        try {
            fx.service.setNewSessionDefault(SessionPermissionMode.CUSTOM, 1000L)
            fail("expected the repository to refuse a CUSTOM app default")
        } catch (expected: IllegalArgumentException) {
            // CUSTOM needs an explicit per-session snapshot
        }
        // a fresh install's default is still READ_ONLY
        assertEquals(SessionPermissionMode.READ_ONLY, fx.configs.appDefault().mode)
        assertTrue(fx.audit.isEmpty())
    }

    @Test
    fun disablingAToolStoresTheRowAndAuditsTheNewStateAndScope() {
        val fx = Fixture()
        val changed =
            fx.service.setToolAvailability(
                "built-in",
                "fs.write",
                ToolAvailabilityScope.WORKSPACE,
                "w1",
                true,
                1000L,
            )
        assertTrue(changed)
        assertEquals(
            ToolAvailabilityState.DISABLED,
            fx.availability.statesFor("built-in", "fs.write", null, "w1").workspace,
        )
        val audit = fx.audit.single()
        assertEquals("tool_availability", audit.action())
        assertEquals("fs.write", audit.str("toolName"))
        assertEquals("DISABLED", audit.str("toolState"))
        assertEquals("WORKSPACE", audit.str("scope"))
        assertEquals("w1", audit.correlationId)
    }

    @Test
    fun enablingAToolRemovesTheRowAndAuditsEnabledNeverAsk() {
        val fx = Fixture()
        fx.service.setToolAvailability("built-in", "fs.write", ToolAvailabilityScope.SESSION, "s1", true, 1000L)
        val changed =
            fx.service.setToolAvailability(
                "built-in",
                "fs.write",
                ToolAvailabilityScope.SESSION,
                "s1",
                false,
                2000L,
            )
        assertTrue(changed)
        // the slot is EMPTY again — re-enabling restores availability, there is no ASK to return to
        assertNull(fx.availability.statesFor("built-in", "fs.write", "s1", null).session)
        val audit = fx.audit[1]
        assertEquals("ENABLED", audit.str("toolState"))
    }

    @Test
    fun enablingAnAlreadyEnabledToolIsANoOpWithNoWriteOrAudit() {
        val fx = Fixture()
        val changed =
            fx.service.setToolAvailability(
                "built-in",
                "fs.write",
                ToolAvailabilityScope.GLOBAL,
                "",
                false,
                1000L,
            )
        assertFalse("enabling a tool with no stored row must be a no-op", changed)
        assertTrue(fx.audit.isEmpty())
    }

    @Test
    fun savingACustomDraftOnAPresetStaysInertAndAuditsTheDraft() {
        val fx = Fixture()
        fx.service.saveSessionConfig("s1", SessionPermissionConfig.of(SessionPermissionMode.WORKSPACE), 1000L)
        val rules = mapOf(OperationEffect.COMMAND_EXECUTION to OperationRule.ASK)
        fx.service.saveCustomDraft("s1", SessionPermissionMode.WORKSPACE, rules, 2000L)
        // the session is on a preset, so the draft is stored but NOT applied to the active config
        assertEquals(SessionPermissionMode.WORKSPACE, fx.configs.forSession("s1")!!.mode)
        val draft = fx.configs.customDraftFor("s1")
        assertEquals(SessionPermissionMode.WORKSPACE, draft?.sourcePreset)
        assertEquals(rules, draft?.rules)
        val audit = fx.audit[1]
        assertEquals("custom_draft", audit.action())
        assertEquals("WORKSPACE", audit.str("mode"))
        assertEquals("WORKSPACE", audit.str("sourcePreset"))
        assertEquals("1", audit.str("configVersion"))
        assertEquals("2000", audit.str("changedAt"))
    }

    @Test
    fun savingACustomDraftWhileOnCustomSyncsTheActiveConfig() {
        val fx = Fixture()
        val first = mapOf(OperationEffect.COMMAND_EXECUTION to OperationRule.DENY)
        fx.service.saveSessionConfig("s1", SessionPermissionConfig.custom(first), 1000L)
        val second = mapOf(OperationEffect.COMMAND_EXECUTION to OperationRule.ASK)
        fx.service.saveCustomDraft("s1", SessionPermissionMode.WORKSPACE, second, 2000L)
        // already CUSTOM: the draft is applied in place, so the active rules never drift
        assertEquals(SessionPermissionMode.CUSTOM, fx.configs.forSession("s1")!!.mode)
        assertEquals(second, fx.configs.forSession("s1")!!.rules)
        val audit = fx.audit[1]
        assertEquals("custom_draft", audit.action())
        assertEquals("CUSTOM", audit.str("mode"))
        assertEquals("WORKSPACE", audit.str("sourcePreset"))
    }

    @Test
    fun readingAMissingDraftIsNullAndProducesNoAudit() {
        val fx = Fixture()
        assertNull(fx.service.customDraftFor("s1"))
        assertTrue(fx.audit.isEmpty())
    }

    @Test
    fun activateCustomDraftAppliesTheStoredSnapshotAndAuditsAConfigChange() {
        val fx = Fixture()
        val rules = mapOf(OperationEffect.FILE_MUTATION_WORKSPACE to OperationRule.DENY)
        fx.service.saveCustomDraft("s1", SessionPermissionMode.READ_ONLY, rules, 1000L)
        assertTrue(fx.service.activateCustomDraft("s1", 2000L))
        assertEquals(SessionPermissionMode.CUSTOM, fx.configs.forSession("s1")!!.mode)
        assertEquals(rules, fx.configs.forSession("s1")!!.rules)
        val audit = fx.audit[1]
        assertEquals("session_config", audit.action())
        assertEquals("CUSTOM", audit.str("mode"))
    }

    @Test
    fun activateCustomDraftWithNoDraftIsANoOp() {
        val fx = Fixture()
        assertFalse(fx.service.activateCustomDraft("s1", 1000L))
        assertNull(fx.configs.forSession("s1"))
        assertTrue(fx.audit.isEmpty())
    }

    @Test
    fun savingACustomDraftDoesNotReEnableADisabledTool() {
        val fx = Fixture()
        fx.service.setToolAvailability("built-in", "fs.write", ToolAvailabilityScope.SESSION, "s1", true, 1000L)
        fx.service.saveCustomDraft("s1", SessionPermissionMode.WORKSPACE, emptyMap(), 2000L)
        // a mode/draft change never touches tool availability — the tool stays disabled
        assertEquals(
            ToolAvailabilityState.DISABLED,
            fx.availability.statesFor("built-in", "fs.write", "s1", null).session,
        )
    }

    /** The real B2 repositories over in-memory DAO fakes + a recording audit seam. */
    private class Fixture {
        val configs =
            SessionPermissionConfigRepository(
                FakeSessionPermissionConfigDao(),
                FakeSessionPermissionDefaultsDao(),
                FakeSessionPermissionDraftDao(),
            )
        val availability = ToolAvailabilityRepository(FakeToolAvailabilityDao())
        val audit = mutableListOf<AuditRecord>()
        private var seq = 0
        val service =
            SessionPermissionEditService(
                configs,
                availability,
                idGenerator = { "evt-${++seq}" },
                appendAudit = { id, correlationId, type, actor, payload, timestamp ->
                    audit.add(AuditRecord(id, correlationId, type, actor, payload, timestamp))
                },
            )
    }

    private class AuditRecord(
        val id: String,
        val correlationId: String,
        val type: String,
        val actor: String,
        val payload: String,
        val timestamp: Long,
    ) {
        private val obj: JsonObject get() = Json.parseToJsonElement(payload) as JsonObject

        fun str(key: String): String = obj.getValue(key).jsonPrimitive.content

        fun action(): String = str("action")
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

    private class FakeSessionPermissionDraftDao : SessionPermissionDraftDao {
        private val rows = LinkedHashMap<String, SessionPermissionDraftEntity>()

        override fun insert(entity: SessionPermissionDraftEntity) {
            rows[entity.sessionId] = entity
        }

        override fun bySession(sessionId: String): SessionPermissionDraftEntity? = rows[sessionId]

        override fun deleteBySession(sessionId: String): Int = rows.remove(sessionId)?.let { 1 } ?: 0
    }
}
