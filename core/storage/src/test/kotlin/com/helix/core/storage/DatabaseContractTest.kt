package com.helix.core.storage

import com.helix.core.storage.internal.MiniJson
import com.helix.core.storage.internal.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * JVM-side contract check for the Room schema export (the committed v1 fixture). The
 * device-side [RoomMigrationFixtureTest] verifies that the code-built schema matches this
 * export; together the two close the drift loop without a device.
 */
class DatabaseContractTest {
    private val expectedTables =
        setOf(
            "sessions",
            "messages",
            "turns",
            "model_calls",
            "tool_calls",
            "tool_results",
            "approvals",
            "executions",
            "artifacts",
            "audit_events",
            "provider_configs",
            "runtime_installs",
            "plans",
            "plan_steps",
            "goals",
            "goal_runs",
            "mcp_servers",
            "mcp_capabilities",
            "skills",
            "skill_snapshots",
            "capability_grants",
            "execution_targets",
        )

    // Every table that declares at least one FOREIGN KEY in the export (14 tables; sessions
    // and goals gained provider/plan references with the v1 index+FK hardening).
    private val fkTables =
        setOf(
            "sessions",
            "messages",
            "turns",
            "model_calls",
            "tool_calls",
            "tool_results",
            "approvals",
            "executions",
            "artifacts",
            "plan_steps",
            "goals",
            "goal_runs",
            "mcp_capabilities",
            "skill_snapshots",
        )

    private fun schemaPath(): File {
        // The build file passes the asset directory explicitly (the JVM unit test runs from a
        // Gradle working directory, so user.dir is not reliable across setups); the relative
        // fallback keeps manual IDE runs working.
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

    private fun loadSchema(): Value.Obj {
        val value = MiniJson.parse(schemaPath().readText())
        return value as? Value.Obj ?: throw AssertionError("schema export must be an object")
    }

    @Test
    fun `exported schema is version 1 with the full section 9-1 table set`() {
        val database = (loadSchema().entries.getValue("database") as Value.Obj)
        assertEquals(1L, (database.entries.getValue("version") as Value.Num).value)
        val entities = (database.entries.getValue("entities") as Value.Arr).items
        val tables =
            entities.map { entity ->
                ((entity as Value.Obj).entries.getValue("tableName") as Value.Str).value
            }
        assertEquals(expectedTables, tables.toSet())
        assertEquals(22, tables.size)
    }

    @Test
    fun `every relation declares a foreign key in its create statement`() {
        val entities =
            loadSchema()
                .entries
                .getValue("database")
                .let { it as Value.Obj }
                .entries
                .getValue("entities") as Value.Arr
        entities.items.forEach { entity ->
            val obj = entity as Value.Obj
            val tableName = (obj.entries.getValue("tableName") as Value.Str).value
            val createSql = (obj.entries.getValue("createSql") as Value.Str).value
            assertTrue("$tableName createSql is empty", createSql.isNotBlank())
            if (tableName in fkTables) {
                assertTrue(
                    "$tableName must declare FOREIGN KEY in its create statement",
                    createSql.contains("FOREIGN KEY"),
                )
            }
        }
    }

    @Test
    fun `secret fields are aliases only in the exported schema`() {
        val entities =
            loadSchema()
                .entries
                .getValue("database")
                .let { it as Value.Obj }
                .entries
                .getValue("entities") as Value.Arr
        entities.items.forEach { entity ->
            val obj = entity as Value.Obj
            val tableName = (obj.entries.getValue("tableName") as Value.Str).value
            if (tableName !in setOf("provider_configs", "mcp_servers")) return@forEach
            val columns =
                (obj.entries.getValue("fields") as Value.Arr).items.map {
                    ((it as Value.Obj).entries.getValue("columnName") as Value.Str)
                        .value
                        .lowercase()
                }
            val forbidden = columns.filter { it in setOf("apikey", "api_key", "token", "secret", "password") }
            assertEquals("$tableName must not store plaintext credentials", emptyList<String>(), forbidden)
        }
        val provider =
            (
                entities.items.first {
                    (it as Value.Obj).entries.getValue("tableName") == Value.Str("provider_configs")
                }
            ) as Value.Obj
        val providerColumns =
            (provider.entries.getValue("fields") as Value.Arr).items.map {
                ((it as Value.Obj).entries.getValue("columnName") as Value.Str).value
            }
        assertTrue("provider_configs must keep the secretAlias column", "secretAlias" in providerColumns)
    }

    @Test
    fun `dao set covers every table`() {
        val daoMethods =
            HelixDatabase::class.java.methods
                .map { it.name }
                .toSet()
        val expectedDaos =
            listOf(
                "sessionDao",
                "messageDao",
                "turnDao",
                "modelCallDao",
                "toolCallDao",
                "toolResultDao",
                "approvalDao",
                "executionDao",
                "artifactDao",
                "auditEventDao",
                "providerConfigDao",
                "runtimeInstallDao",
                "executionTargetDao",
                "capabilityGrantDao",
                "planDao",
                "goalDao",
                "goalRunDao",
                "mcpServerDao",
                "mcpCapabilityDao",
                "skillDao",
                "skillSnapshotDao",
            )
        expectedDaos.forEach { assertTrue("missing DAO accessor: $it", daoMethods.contains(it)) }
    }
}
