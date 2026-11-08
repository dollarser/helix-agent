package com.helix.tools.android

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WebFetchToolTest {
    @Test
    fun realDocumentOutputFitsTheRegisteredSchemaAndCarriesActualSource() {
        val result = run(page("<title>Title</title><main>Useful source evidence</main>"))
        assertEquals("read", result.getValue("status").jsonPrimitive.content)
        assertEquals("Useful source evidence", result.getValue("text").jsonPrimitive.content)
        assertEquals("https://example.com/final", result.getValue("finalUrl").jsonPrimitive.content)
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(WebFetchTool.descriptor().outputSchema!!, result),
        )
    }

    @Test
    fun failuresEmptyPagesAndBinaryAreNotReportedAsRead() {
        assertEquals("http-error", run(page("").copy(status = 403)).getValue("status").jsonPrimitive.content)
        assertEquals(
            "unsupported-type",
            run(page("").copy(mediaType = "application/pdf")).getValue("status").jsonPrimitive.content,
        )
        assertEquals(
            "no-readable-text",
            run(page("<script>onlyJavaScript()</script>")).getValue("status").jsonPrimitive.content,
        )
    }

    @Test
    fun timeoutAndCancellationRemainDistinct() {
        for ((code, expected) in listOf(
            "timed-out" to ToolExecutorResult.TimedOut,
            "cancelled" to ToolExecutorResult.Cancelled,
        )) {
            val executor = WebFetchTool.executor { _, _, _ -> throw WebFetchFailure(code) }
            assertEquals(expected, executor.execute(call()))
        }
    }

    @Test
    fun invalidUrlFailsBeforeTransportAndRefusalIsNotSuccess() {
        val never = WebFetchTool.executor { _, _, _ -> error("must not send") }
        for (url in listOf("file:///private", "https://bad_host.example/", "https:example.com")) {
            assertTrue(never.execute(call(url)) is ToolExecutorResult.Failed)
        }
        val encoded = WebFetchTool.executor { _, _, _ -> throw WebFetchFailure("unsupported-content-encoding") }
        assertTrue(encoded.execute(call()) is ToolExecutorResult.Failed)
        val refused = WebFetchTool.executor { _, _, _ -> throw WebFetchFailure("network-scope_violation") }
        assertTrue(refused.execute(call()) is ToolExecutorResult.Failed)
    }

    private fun run(page: WebFetchPage): JsonObject =
        (WebFetchTool.executor { _, _, _ -> page }.execute(call()) as ToolExecutorResult.Completed).output.jsonObject

    private fun call(url: String = "https://example.com/request?q=1#section") =
        ExecutableToolCall(
            "web-call",
            WebFetchTool.NAME,
            "1",
            buildJsonObject { put("url", url) },
            ExecutionTargetType.LOCAL_ANDROID,
            Instant.now().plusSeconds(10),
            NoCancellation,
        )

    private fun page(text: String) =
        WebFetchPage("https://example.com/final", 200, "text/html", "UTF-8", text.toByteArray(), false, 1)
}
