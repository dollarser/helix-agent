package com.helix.app.eval

import com.helix.extensions.mcp.McpClientSession
import com.helix.extensions.mcp.McpMetadataLimits
import com.helix.extensions.mcp.McpMetadataSnapshot
import com.helix.extensions.mcp.McpResourceBlob
import com.helix.extensions.mcp.McpResourcePage
import com.helix.extensions.mcp.McpResourceTools
import com.helix.extensions.mcp.McpServerIdentity
import com.helix.extensions.mcp.McpToolResult
import com.helix.extensions.mcp.McpToolResultLimits
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Device-side cursor contract only: no network, model, account or permission changes. */
class McpCursorDeviceTest {
    @Test
    fun emptyCursorIsFirstPageAndMalformedContinuationDoesNotRead() =
        runBlocking {
            val session = CursorSession()
            for (name in listOf("list_mcp_resources", "list_mcp_resource_templates")) {
                val omitted = McpResourceTools.execute(session, name, buildJsonObject {})
                val empty = McpResourceTools.execute(session, name, buildJsonObject { put("cursor", "") })
                assertEquals(omitted.structuredContent, empty.structuredContent)
                val before = session.cursors.size
                val failure =
                    runCatching {
                        McpResourceTools.execute(session, name, buildJsonObject { put("cursor", "invalid") })
                    }.exceptionOrNull()
                assertTrue(failure is IllegalArgumentException)
                assertEquals(before, session.cursors.size)
            }
            assertEquals(listOf<String?>(null, null, null, null), session.cursors)
        }

    private class CursorSession : McpClientSession {
        override val server = McpServerIdentity("cursor-fixture", "1", "2025-03-26")
        val cursors = mutableListOf<String?>()

        override suspend fun ping() = Unit

        override suspend fun close() = Unit

        override suspend fun snapshotMetadata(limits: McpMetadataLimits): McpMetadataSnapshot = error("Not used")

        override suspend fun callTool(
            name: String,
            arguments: JsonObject,
            limits: McpToolResultLimits,
        ): McpToolResult = error("Not used")

        override suspend fun readResource(uri: String): List<McpResourceBlob> = error("Not used")

        override suspend fun listResources(cursor: String?): McpResourcePage {
            cursors += cursor
            return McpResourcePage(emptyList(), null)
        }

        override suspend fun listResourceTemplates(cursor: String?): McpResourcePage = listResources(cursor)
    }
}
