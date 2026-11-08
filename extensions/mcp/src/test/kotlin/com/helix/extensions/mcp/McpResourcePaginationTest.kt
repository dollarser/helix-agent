package com.helix.extensions.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpResourcePaginationTest {
    @Test
    fun sixteenthFinalPageIsReadableButSeventeenthIsExplicitFailure() =
        runBlocking {
            var cursor: String? = null
            val session = Pages(16)
            repeat(16) { index ->
                val page = resourcePage(session, false, cursor, "source", {})
                assertEquals(
                    "memo://$index",
                    page.entries
                        .single()["uri"]
                        ?.toString()
                        ?.trim('"'),
                )
                cursor = page.nextCursor
            }
            assertNull(cursor)
            var oversizedCursor: String? = null
            repeat(15) { oversizedCursor = resourcePage(Pages(17), false, oversizedCursor, "source", {}).nextCursor }
            assertTrue(runCatching { resourcePage(Pages(17), false, oversizedCursor, "source", {}) }.isFailure)
        }

    @Test
    fun changedPriorMetadataAndCumulativeMetadataOverflowFailClosed() =
        runBlocking {
            val first = resourcePage(Pages(8), false, null, "source", {})
            assertTrue(
                runCatching { resourcePage(Pages(8, "changed"), false, first.nextCursor, "source", {}) }.isFailure,
            )
            var cursor: String? = null
            repeat(4) { cursor = resourcePage(Pages(8, "a".repeat(240000)), false, cursor, "source", {}).nextCursor }
            assertTrue(
                runCatching { resourcePage(Pages(8, "a".repeat(240000)), false, cursor, "source", {}) }.isFailure,
            )
        }
}

private class Pages(
    private val count: Int,
    private val name: String = "name",
) : McpClientSession {
    override val server = McpServerIdentity("fixture", "1", "2025-03-26")

    override suspend fun listResources(cursor: String?): McpResourcePage {
        val page = cursor?.toInt() ?: 0
        return McpResourcePage(
            listOf(
                buildJsonObject {
                    put("uri", "memo://$page")
                    put("name", name)
                },
            ),
            (page + 1).takeIf { it < count }?.toString(),
        )
    }

    override suspend fun listResourceTemplates(cursor: String?): McpResourcePage = error("unused")

    override suspend fun readResource(uri: String): List<McpResourceText> = error("unused")

    override suspend fun snapshotMetadata(limits: McpMetadataLimits): McpMetadataSnapshot = error("unused")

    override suspend fun callTool(
        name: String,
        arguments: JsonObject,
        limits: McpToolResultLimits,
    ): McpToolResult = error("unused")

    override suspend fun ping() = Unit

    override suspend fun close() = Unit
}
