package com.helix.extensions.mcp

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpTemplateMatchingBudgetTest {
    @Test(timeout = 3000)
    fun cumulativeRemoteTemplatePageCannotMultiplyCpuBudget() =
        runBlocking {
            val session = TemplatePage()
            val failure =
                runCatching {
                    McpResourceTools.execute(session, "read_mcp_resource", buildJsonObject { put("uri", LONG_URI) })
                }.exceptionOrNull()
            assertEquals(McpResourceFailure.MATCH_BUDGET_EXCEEDED, (failure as? McpResourceException)?.reason)
            assertFalse(session.readCalled)
        }

    @Test(timeout = 3000)
    fun matchingYieldsToQueuedCancellationBeforeCompleting() =
        runBlocking {
            var finished = false
            val matching =
                async(start = CoroutineStart.UNDISPATCHED) {
                    matchesSimpleTemplate(LONG_TEMPLATE, LONG_URI)
                    finished = true
                }
            matching.cancel()
            assertTrue(runCatching { matching.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertFalse(finished)
        }

    @Test(timeout = 3000)
    fun rowCheckYieldsToSameLoopDeadlineAndHonorsRevocation() =
        runBlocking {
            var checks = 0
            val timeout =
                runCatching {
                    withTimeout(1) {
                        matchesSimpleTemplate(LONG_TEMPLATE, LONG_URI) {
                            if (++checks == 1) Thread.sleep(10)
                        }
                    }
                }.exceptionOrNull()
            assertTrue(timeout is TimeoutCancellationException)
            checks = 0
            val revoked =
                runCatching {
                    matchesSimpleTemplate(LONG_TEMPLATE, LONG_URI) { check(++checks < 2) { "revoked" } }
                }.exceptionOrNull()
            assertTrue(revoked is IllegalStateException)
            assertEquals(2, checks)
        }
}

private const val PREFIX = "memo://"
private val LONG_TEMPLATE = PREFIX + "a".repeat(4000) + "{x}Z"
private val LONG_URI = PREFIX + "a".repeat(4000) + "end"

private class TemplatePage : McpClientSession {
    override val server = McpServerIdentity("fixture", "1", "2025-03-26")
    var readCalled = false

    override suspend fun listResources(cursor: String?) = McpResourcePage(emptyList(), null)

    override suspend fun listResourceTemplates(cursor: String?) =
        McpResourcePage(
            List(60) {
                buildJsonObject {
                    put("uriTemplate", LONG_TEMPLATE)
                    put("name", "item")
                }
            },
            null,
        )

    override suspend fun readResource(uri: String): List<McpResourceText> {
        readCalled = true
        error("must stop before read")
    }

    override suspend fun snapshotMetadata(limits: McpMetadataLimits): McpMetadataSnapshot = error("unused")

    override suspend fun callTool(
        name: String,
        arguments: JsonObject,
        limits: McpToolResultLimits,
    ): McpToolResult = error("unused")

    override suspend fun ping() = Unit

    override suspend fun close() = Unit
}
