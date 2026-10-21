package com.helix.app.knowledge

import com.helix.core.model.ExecutionTargetType
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class KnowledgeReadToolsTest {
    private var allowed = true
    private var cancelled = false
    private var reads = 0
    private var revokeDuringRead = false
    private val backend =
        object : KnowledgeReadTools.Backend {
            override fun allowed() = allowed

            override suspend fun search(query: String): JsonObject {
                reads++
                if (revokeDuringRead) allowed = false
                return Json.parseToJsonElement("""{"items":[]}""") as JsonObject
            }

            override suspend fun read(
                kind: String,
                id: String,
            ): JsonObject? {
                search(id)
                return null
            }
        }

    private fun run(
        args: String,
        search: Boolean = true,
    ): ToolExecutorResult {
        val call =
            ExecutableToolCall(
                "call",
                "knowledge.search",
                "1",
                Json.parseToJsonElement(args) as JsonObject,
                ExecutionTargetType.LOCAL_ANDROID,
                Instant.now().plusSeconds(30),
                object : CancelSignal {
                    override fun isCancelled() = cancelled
                },
            )
        return KnowledgeReadTools.executor(search, backend).execute(call)
    }

    @Test fun disabledAccessDoesNotReadAnything() {
        allowed = false
        assertTrue(run("""{"query":"budget"}""") is ToolExecutorResult.Failed)
        assertEquals(0, reads)
    }

    @Test fun cancelledAccessDoesNotReadAnything() {
        cancelled = true
        assertEquals(ToolExecutorResult.Cancelled, run("""{"query":"budget"}"""))
        assertEquals(0, reads)
    }

    @Test fun revocationDuringReadDoesNotDeliverData() {
        revokeDuringRead = true
        assertTrue(run("""{"query":"budget"}""") is ToolExecutorResult.Failed)
        assertEquals(1, reads)
    }

    @Test fun queryIsBoundedAndUnknownArgumentsRefused() {
        listOf(
            """{"query":" "}""",
            """{"query":"budget","all":true}""",
            """{"query":"${"x".repeat(201)}"}""",
        ).forEach {
            assertTrue(run(it) is ToolExecutorResult.Failed)
        }
        assertEquals(0, reads)
    }

    @Test fun arbitraryPathsAreNotLibraryReferences() {
        listOf("../note", "file:///note", "/data/note").forEach {
            assertTrue(run("""{"kind":"note","id":"$it"}""", false) is ToolExecutorResult.Failed)
        }
        assertEquals(0, reads)
    }

    @Test fun deletedItemReportsFailureRatherThanEmptySuccess() {
        assertTrue(run("""{"kind":"source","id":"removed"}""", false) is ToolExecutorResult.Failed)
        assertEquals(1, reads)
    }

    @Test fun validSearchReturnsBackendResultAndSchemasRejectExtraInput() {
        assertTrue(run("""{"query":"budget"}""") is ToolExecutorResult.Completed)
        val schema = KnowledgeReadTools.descriptor(true).inputSchema
        assertTrue(
            ToolSchemaValidator.validate(schema, Json.parseToJsonElement("""{"query":"x","all":true}""")) !=
                ToolSchemaValidation.Valid,
        )
    }
}
