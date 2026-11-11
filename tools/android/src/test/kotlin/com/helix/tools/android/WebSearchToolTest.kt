package com.helix.tools.android

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant

class WebSearchToolTest {
    @Test
    fun unconfiguredFailsBeforeTransport() {
        val tool = WebSearchTool.executor({ null }) { _, _, _ -> error("must not send") }
        assertTrue(tool.execute(call()).toString().contains("not-configured"))
    }

    @Test
    fun boundedSourceSnippetsFitSchemaAndNeverIncludeCredentialOrProviderAnswer() {
        val tool =
            WebSearchTool.executor(
                { WebSearchConfiguration(WebSearchProvider.TAVILY, "test-only-secret") },
            ) { _, _, _ ->
                """
                {"answer":"unsupported answer","results":[
                  {"url":"https://example.com/source","title":"Title","content":"Snippet"}
                ]}
                """.trimIndent().toByteArray()
            }
        val output = (tool.execute(call()) as ToolExecutorResult.Completed).output.jsonObject
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(WebSearchTool.descriptor().outputSchema!!, output),
        )
        assertFalse(output.toString().contains("test-only-secret"))
        assertFalse(output.toString().contains("unsupported answer"))
        assertTrue(output.toString().contains("https://example.com/source"))
    }

    @Test
    fun malformedOrUnsafeSourcesFailClosed() {
        for (fixture in listOf("{}", "[]", """{"results":[{"title":"x","url":"file:///secret","content":"x"}]}""")) {
            val tool =
                WebSearchTool.executor({
                    WebSearchConfiguration(WebSearchProvider.TAVILY, "test-only")
                }) { _, _, _ -> fixture.toByteArray() }
            assertTrue(tool.execute(call()) is ToolExecutorResult.Failed)
        }
    }

    @Test
    fun cancelledExpiredAndTransportFailureNeverReplayOrLeakException() {
        var attempts = 0
        val tool =
            WebSearchTool.executor({ WebSearchConfiguration(WebSearchProvider.TAVILY, "test-only") }) { _, _, _ ->
                attempts++
                throw IOException("credential-do-not-show")
            }
        val failed = tool.execute(call())
        assertEquals(1, attempts)
        assertFalse(failed.toString().contains("credential-do-not-show"))
        assertTrue(failed.toString().contains("quota outcome unknown"))
        assertEquals(ToolExecutorResult.TimedOut, tool.execute(call().copy(deadline = Instant.EPOCH)))
        assertEquals(1, attempts)
    }

    @Test
    fun truncationIsExplicitAndNeverSplitsUnicodeAndDeepJsonFails() {
        val row =
            buildJsonObject {
                put("title", "x".repeat(511) + "😀")
                put("content", "x".repeat(3999) + "😀")
                put("url", "https://example.com")
            }
        val fixture =
            buildJsonObject {
                put("results", kotlinx.serialization.json.JsonArray(List(6) { row }))
            }
        val result =
            WebSearchTool.parseResults(
                fixture.toString().toByteArray(),
                WebSearchConfiguration(WebSearchProvider.TAVILY, "fixture-secret"),
            )
        assertEquals(kotlinx.serialization.json.JsonPrimitive(true), result["truncated"])
        assertFalse(result.toString().contains("\uFFFD"))
        val rows = result["results"] as kotlinx.serialization.json.JsonArray
        assertEquals(5, rows.size)
        assertEquals(511, (rows[0].jsonObject["title"] as kotlinx.serialization.json.JsonPrimitive).content.length)
        org.junit.Assert.assertThrows(WebFetchFailure::class.java) {
            WebSearchTool.parseResults(
                ("[".repeat(1000) + "]".repeat(1000)).toByteArray(),
                WebSearchConfiguration(WebSearchProvider.TAVILY, "fixture-secret"),
            )
        }
    }

    @Test
    fun escapedCredentialReflectionFailsBeforeOutputOrTruncation() {
        val key = "synthetic-secret"
        val escaped = key.map { "\\u%04x".format(it.code) }.joinToString("")
        for (field in listOf("title", "content", "url")) {
            val item =
                buildJsonObject {
                    put("title", "Title")
                    put("content", "Snippet")
                    put("url", "https://example.com/source")
                    put(field, "REFLECTED_CREDENTIAL")
                }
            val fixture = """{"results":[$item]}""".replace("REFLECTED_CREDENTIAL", escaped)
            assertFalse(fixture.contains(key))
            val tool =
                WebSearchTool.executor({
                    WebSearchConfiguration(WebSearchProvider.TAVILY, key)
                }) { _, _, _ -> fixture.toByteArray() }
            val result = tool.execute(call())
            assertTrue(result is ToolExecutorResult.Failed)
            assertTrue(result.toString().contains("credential-reflected-in-response"))
            assertFalse(result.toString().contains(key))
        }
        val fixture = """{"results":[{"title":"Title","url":"https://example.com","content":"${"x".repeat(
            3995,
        )}$escaped"}]}"""
        val tool =
            WebSearchTool.executor({
                WebSearchConfiguration(WebSearchProvider.TAVILY, key)
            }) { _, _, _ -> fixture.toByteArray() }
        assertTrue(tool.execute(call()) is ToolExecutorResult.Failed)
    }

    @Test
    fun deepSeekUsesSummaryAndBoundsUnicodeQueryBeforeSending() {
        var attempts = 0
        val config = WebSearchConfiguration(WebSearchProvider.DEEPSEEK, "synthetic-secret")
        val tool =
            WebSearchTool.executor({ config }) { _, configuration, _ ->
                assertEquals(config, configuration)
                attempts++
                """
                {"results":[{"title":"Title","url":"https://example.com",
                "summary":"Summary","content":"Not fetched"}]}
                """.trimIndent().toByteArray()
            }
        val output = (tool.execute(call()) as ToolExecutorResult.Completed).output.jsonObject
        assertEquals(kotlinx.serialization.json.JsonPrimitive("deepseek"), output["provider"])
        assertTrue(output.toString().contains("Summary"))
        assertFalse(output.toString().contains("Not fetched"))
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(WebSearchTool.descriptor().outputSchema!!, output),
        )
        val atLimit = call().copy(args = buildJsonObject { put("query", "😀".repeat(128)) })
        assertTrue(tool.execute(atLimit) is ToolExecutorResult.Completed)
        val overLimit = call().copy(args = buildJsonObject { put("query", "😀".repeat(129)) })
        assertTrue(tool.execute(overLimit).toString().contains("deepseek-query-too-long"))
        assertEquals(2, attempts)
    }

    @Test
    fun deepSeekEmptyMalformedAndReflectedResults() {
        val config = WebSearchConfiguration(WebSearchProvider.DEEPSEEK, "synthetic-secret")
        val empty = WebSearchTool.executor({ config }) { _, _, _ -> """{"results":[]}""".toByteArray() }
        assertTrue(empty.execute(call()) is ToolExecutorResult.Completed)
        for (summary in listOf("null", "123", "\"synthetic-secret\"", "\"\\u0073ynthetic-secret\"")) {
            val tool =
                WebSearchTool.executor({ config }) { _, _, _ ->
                    """{"results":[{"title":"Title","url":"https://example.com","summary":$summary}]}""".toByteArray()
                }
            assertTrue(tool.execute(call()) is ToolExecutorResult.Failed)
        }
    }

    private fun call() =
        ExecutableToolCall(
            "search-test",
            WebSearchTool.NAME,
            "1",
            buildJsonObject { put("query", "offline fixture") },
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(10),
            NoCancellation,
        )
}
