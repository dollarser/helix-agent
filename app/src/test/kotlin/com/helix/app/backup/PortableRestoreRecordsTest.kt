package com.helix.app.backup

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortableRestoreRecordsTest {
    private fun row(vararg pairs: Pair<String, String?>) =
        JsonObject(
            pairs.associate { (key, value) ->
                key to (value?.let(::JsonPrimitive) ?: JsonNull)
            },
        )

    private fun records() =
        JsonObject(
            mapOf(
                "workspaces" to JsonArray(listOf(row("id" to "ws-source", "ownerSessionId" to "session"))),
                "projects" to JsonArray(listOf(row("workspaceId" to "ws-source"))),
                "session_workspaces" to JsonArray(listOf(row("workspaceId" to "ws-source"))),
                "model_call_workspaces" to JsonArray(listOf(row("workspaceId" to "ws-source"))),
                "sessions" to JsonArray(listOf(row("directoryRef" to "/old/device/path"))),
                "artifacts" to
                    JsonArray(
                        listOf(
                            row(
                                "id" to "attachment",
                                "relativePath" to "scope:external:private.txt",
                            ),
                        ),
                    ),
                "provider_configs" to
                    JsonArray(
                        listOf(
                            row(
                                "provisioningKind" to "USER_CONFIGURED",
                                "authKind" to "SECRET",
                                "secretAlias" to "synthetic",
                            ),
                            row(
                                "provisioningKind" to "BUILT_IN",
                                "authKind" to "SECRET",
                                "secretAlias" to "helix-account-session",
                            ),
                        ),
                    ),
                "mcp_servers" to JsonArray(emptyList()),
                "a2a_agents" to JsonArray(emptyList()),
            ),
        )

    @Test fun currentDirectoryRebindDoesNotRetargetHistoricalRequests() {
        val plan = PortableRestoreRecords(records(), setOf("attachment"), emptyMap())
        val clones = AppBackupCapture.rows(plan.records, "workspaces")
        assertEquals(2, clones.size)
        assertEquals(JsonNull, clones.first()["ownerSessionId"])
        val id = plan.workspaceClones.getValue("ws-source")
        assertNotEquals("ws-source", id)
        val current = AppBackupCapture.rows(plan.records, "session_workspaces").single()
        val historical = AppBackupCapture.rows(plan.records, "model_call_workspaces").single()
        assertEquals(id, AppBackupCapture.text(current, "workspaceId"))
        assertEquals("ws-source", AppBackupCapture.text(historical, "workspaceId"))
        assertEquals(JsonNull, AppBackupCapture.rows(plan.records, "sessions").single()["directoryRef"])
        val attachment = AppBackupCapture.rows(plan.records, "artifacts").single()
        val expectedPath = "scope:app:restored-${plan.restoreId}/attachments/attachment"
        assertEquals(expectedPath, AppBackupCapture.text(attachment, "relativePath"))
    }

    @Test fun credentialAliasesAreFreshAndAccountSessionsCannotBeImported() {
        val plan = PortableRestoreRecords(records(), emptySet(), mapOf("synthetic" to "FAKE_KEY"))
        assertFalse(plan.credentials.containsKey("synthetic"))
        val providers = AppBackupCapture.rows(plan.records, "provider_configs")
        val alias = AppBackupCapture.text(providers.first(), "secretAlias")
        assertEquals("FAKE_KEY", plan.credentials[alias])
        assertEquals(JsonNull, providers.last()["secretAlias"])
        assertThrows(IllegalArgumentException::class.java) {
            PortableRestoreRecords(records(), emptySet(), mapOf("helix-account-session" to "FAKE_SESSION"))
        }
    }
}
