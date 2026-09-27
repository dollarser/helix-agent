package com.helix.core.storage

import com.helix.core.storage.internal.MiniJson
import com.helix.core.storage.internal.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** JVM contract for the single pre-release Room baseline exported as schema version 1. */
class DatabaseContractTest {
    private val expectedTables =
        setOf(
            "sessions",
            "workspaces",
            "session_workspaces",
            "model_call_workspaces",
            "connector_installations",
            "connector_skill_ownership",
            "session_connectors",
            "connector_endpoints",
            "messages",
            "message_attachments",
            "message_reference_snapshots",
            "turns",
            "model_calls",
            "tool_calls",
            "tool_results",
            "approvals",
            "interaction_receipts",
            "executions",
            "artifacts",
            "audit_events",
            "provider_configs",
            "runtime_installs",
            "plans",
            "plan_steps",
            "goals",
            "goal_runs",
            "goal_turn_bindings",
            "goal_usage_reservations",
            "mcp_servers",
            "mcp_capabilities",
            "skills",
            "skill_snapshots",
            "capability_grants",
            "execution_targets",
            "high_sensitivity_rules",
            "a2a_agents",
            "a2a_capabilities",
            "a2a_tasks",
            "goal_controls",
            "session_permission_configs",
            "tool_availability",
            "session_permission_defaults",
            "session_permission_drafts",
            "session_experts",
            "session_run_controls",
            "composer_drafts",
            "session_inputs",
            "session_input_attachments",
            "tool_call_reviews",
            "turn_runtime_records",
            "turn_review_receipts",
        )

    private fun schemaPath(): File {
        val property = System.getProperty("helix.schema.dir")
        val candidate =
            if (property.isNullOrBlank()) {
                File("src/androidTest/assets/com.helix.core.storage.HelixDatabase/1.json")
            } else {
                File(property, "com.helix.core.storage.HelixDatabase/1.json")
            }
        assertTrue("schema export not found at ${candidate.absolutePath}", candidate.isFile)
        return candidate
    }

    private fun database(): Value.Obj {
        val root = MiniJson.parse(schemaPath().readText()) as? Value.Obj ?: error("schema export must be an object")
        return root.entries.getValue("database") as Value.Obj
    }

    private fun entities(): List<Value.Obj> =
        ((database().entries.getValue("entities") as Value.Arr).items).map { it as Value.Obj }

    private fun entity(table: String): Value.Obj =
        entities().single { (it.entries.getValue("tableName") as Value.Str).value == table }

    private fun columns(table: String): List<String> =
        (entity(table).entries.getValue("fields") as Value.Arr).items.map {
            ((it as Value.Obj).entries.getValue("columnName") as Value.Str).value
        }

    @Test
    fun `exported schema is the complete version one baseline`() {
        val database = database()
        assertEquals(1L, (database.entries.getValue("version") as Value.Num).value)
        val tables = entities().map { (it.entries.getValue("tableName") as Value.Str).value }
        assertEquals(expectedTables, tables.toSet())
        assertEquals(51, tables.size)
    }

    @Test
    fun `foreign-key metadata is reflected in every relation create statement`() {
        entities().forEach { entity ->
            val table = (entity.entries.getValue("tableName") as Value.Str).value
            val createSql = (entity.entries.getValue("createSql") as Value.Str).value
            val foreignKeys = entity.entries["foreignKeys"] as? Value.Arr
            assertTrue("$table createSql is empty", createSql.isNotBlank())
            if (foreignKeys?.items?.isNotEmpty() == true) {
                assertTrue("$table must declare FOREIGN KEY", createSql.contains("FOREIGN KEY"))
            }
        }
    }

    @Test
    fun `current turn recovery and review facts are first-class baseline columns`() {
        assertTrue("recoveryFromTurnId" in columns("turns"))
        assertEquals(
            listOf("id", "turnId", "callId", "name", "version", "argsJson", "argsHash", "state", "modelIntent"),
            columns("tool_calls"),
        )
        assertEquals(
            listOf(
                "turnId",
                "version",
                "providerId",
                "modelId",
                "providerSnapshot",
                "mode",
                "chatToolsEnabled",
                "budgetsJson",
                "reasoning",
                "goalBudgetsJson",
                "expertProfileJson",
                "consumedModelCalls",
                "consumedTokens",
                "admittedToolRounds",
            ),
            columns("turn_runtime_records"),
        )
        assertEquals(
            listOf("turnId", "clientActionId", "actionFingerprint"),
            columns("turn_review_receipts"),
        )
        assertEquals(
            listOf("toolCallId", "decision", "reviewedAt"),
            columns("tool_call_reviews"),
        )
    }

    @Test
    fun `session workbench facts are first class baseline columns`() {
        assertEquals(
            listOf(
                "sessionId",
                "mode",
                "chatToolsEnabled",
                "turnBudgetsJson",
                "reasoning",
                "goalBudgetsJson",
                "configVersion",
                "revision",
                "createdAtEpoch",
                "updatedAtEpoch",
            ),
            columns("session_run_controls"),
        )
        assertEquals(
            listOf(
                "sessionId",
                "profileId",
                "displayName",
                "instruction",
                "recommendedSkillIdsJson",
                "recommendedConnectorIdsJson",
                "recommendedMode",
                "revision",
                "createdAtEpoch",
                "updatedAtEpoch",
            ),
            columns("session_experts"),
        )
        assertEquals(
            listOf(
                "rowId",
                "messageId",
                "ordinal",
                "sourceSessionId",
                "sourceSessionTitle",
                "selectionKind",
                "sourceMessageIdsJson",
                "contentRef",
                "contentSha256",
                "createdAtEpoch",
            ),
            columns("message_reference_snapshots"),
        )
        assertTrue("referenceSourceSessionId" in columns("composer_drafts"))
        assertTrue("referenceKind" in columns("composer_drafts"))
        assertTrue("referenceContentRef" in columns("session_inputs"))
        assertTrue("referenceContentSha256" in columns("session_inputs"))
    }

    @Test
    fun `approval binding and secret storage stay fail closed in the baseline`() {
        assertEquals(
            listOf("id", "toolCallId", "bindingHash", "decision", "decidedAt", "consumedAt", "expiresAt"),
            columns("approvals"),
        )
        for (table in listOf("provider_configs", "mcp_servers")) {
            val lower = columns(table).map(String::lowercase)
            assertFalse(lower.any { it in setOf("apikey", "api_key", "token", "secret", "password") })
        }
        assertTrue("secretAlias" in columns("provider_configs"))
    }

    @Test
    fun `legacy internal tables are absent from the baseline`() {
        val tables = entities().map { (it.entries.getValue("tableName") as Value.Str).value }.toSet()
        assertFalse("tool_approval_preferences" in tables)
        assertFalse("tool_registration_baseline" in tables)
        assertFalse("tool_baseline_meta" in tables)
    }
}
