package com.helix.app.knowledge

import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class KnowledgeReadToolsTest {
    private var allowed = true
    private var cancelled = false
    private var reads = 0
    private var revokeDuringRead = false
    private var document: JsonObject? = null
    private val backend =
        object : KnowledgeReadTools.Backend {
            override fun allowed() = allowed

            override fun checkCaller(call: ExecutableToolCall) = Unit

            override fun references(
                call: ExecutableToolCall,
                inputId: String,
                hash: String,
            ): List<com.helix.core.model.input.KnowledgeReference> = error("No references in this fixture")

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
                return document
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

    @Test fun integerPaginationUsesExactNumbersAndDefaultsOnlyWhenAbsent() {
        document =
            Json.parseToJsonElement(
                """{"title":"Test","text":"abc","contentHash":"${knowledgeHash("abc")}"}""",
            ) as JsonObject
        val schema = KnowledgeReadTools.descriptor(false).inputSchema
        for (numbers in listOf("\"offset\":0.0,\"limit\":1e0", "\"offset\":0e2,\"limit\":1.000")) {
            val args = """{"kind":"note","id":"test",$numbers}"""
            assertEquals(
                ToolSchemaValidation.Valid,
                ToolSchemaValidator.validate(schema, Json.parseToJsonElement(args)),
            )
            val result = run(args, false) as ToolExecutorResult.Completed
            assertEquals(
                "\"a\"",
                result.output.jsonObject
                    .getValue("text")
                    .toString(),
            )
        }
        val default = run("""{"kind":"note","id":"test"}""", false) as ToolExecutorResult.Completed
        assertEquals(
            "\"abc\"",
            default.output.jsonObject
                .getValue("text")
                .toString(),
        )
        for (number in listOf("null", "\"1\"", "1.00000000000000001", "2147483648", "1e-400")) {
            val args = """{"kind":"note","id":"test","limit":$number}"""
            assertTrue(
                "Must reject supplied $number instead of defaulting",
                run(args, false) is ToolExecutorResult.Failed,
            )
        }
    }

    @Test fun readToolsAreAvailableInReviewModesAndChatRequiresItsToolSwitch() {
        val descriptors = listOf(KnowledgeReadTools.descriptor(true), KnowledgeReadTools.descriptor(false))
        for (mode in listOf(com.helix.core.model.AgentMode.PLAN, com.helix.core.model.AgentMode.ACT)) {
            val visible =
                com.helix.core.agent.ModePolicy.filterTools(mode, descriptors, false) {
                    com.helix.core.agent
                        .ToolModeProfile(it.operationClass)
                }
            assertEquals(descriptors, visible)
        }
        assertTrue(
            com.helix.core.agent.ModePolicy
                .filterTools(
                    com.helix.core.model.AgentMode.CHAT,
                    descriptors,
                    false,
                ) {
                    com.helix.core.agent
                        .ToolModeProfile(it.operationClass)
                }.isEmpty(),
        )
        assertEquals(
            descriptors,
            com.helix.core.agent.ModePolicy.filterTools(
                com.helix.core.model.AgentMode.CHAT,
                descriptors,
                true,
            ) {
                com.helix.core.agent
                    .ToolModeProfile(it.operationClass)
            },
        )
    }
}
