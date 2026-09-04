package com.helix.tools.android

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolOperationClass
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * HXA-066 (verification matrix row `:tools:android:testDebugUnitTest`): the `http.fetch` tool's
 * fail-closed outcome → result mapping. A [FakeHttpFetchBridge] returns canned port outcomes and
 * captures the request the tool built; each branch must be pinned — a fetch and a policy REFUSAL
 * both become Completed (the refusal carries the STABLE reason code and an empty body, never a fake
 * success), a TIMEOUT maps to [ToolExecutorResult.TimedOut], an ERROR maps to Failed with a bounded
 * reason, invalid/missing arguments fail, and the emitted output carries no JSON nulls. The real
 * resolve → check → connect → peer-revalidate → redirect transport lives in the port impl (this
 * module) and is exercised on device.
 */
class HttpFetchToolsMappingTest {
    private val bridge = FakeHttpFetchBridge()

    private val noCancel =
        object : CancelSignal {
            override fun isCancelled(): Boolean = false
        }

    private fun call(args: JsonObject): ExecutableToolCall =
        ExecutableToolCall(
            toolCallId = "call-1",
            toolName = HttpFetchTool.NAME,
            toolVersion = "1",
            args = args,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            deadline = DEADLINE,
            cancel = noCancel,
        )

    private fun run(args: JsonObject): ToolExecutorResult = HttpFetchTool.executor(bridge).execute(call(args))

    private fun json(result: ToolExecutorResult): JsonObject {
        val c = result as? ToolExecutorResult.Completed ?: error("expected Completed, got $result")
        return c.output.jsonObject
    }

    private fun failed(result: ToolExecutorResult): ToolExecutorResult.Failed {
        val f = result as? ToolExecutorResult.Failed ?: error("expected Failed, got $result")
        return f
    }

    /** Walks a result's Completed output and asserts it carries no JSON nulls anywhere. */
    private fun assertNoNulls(result: ToolExecutorResult) {
        val c = result as? ToolExecutorResult.Completed ?: error("expected Completed, got $result")

        fun walk(e: JsonElement) {
            assertTrue("found a JSON null in tool output", e !is JsonNull)
            when (e) {
                is JsonObject -> e.values.forEach { walk(it) }
                is JsonArray -> e.forEach { walk(it) }
                else -> Unit
            }
        }
        walk(c.output)
    }

    // ── outcome → result mapping ─────────────────────────────────────────────────────────

    @Test
    fun fetchedEmitsAllFields() {
        bridge.outcome =
            HttpFetchOutcome(
                status = HttpFetchStatus.FETCHED,
                finalUrl = "http://example.com/api",
                httpStatus = 200,
                contentType = "text/plain; charset=utf-8",
                body = "hello world",
                bodyBytes = 11,
                truncated = false,
                redirectCount = 1,
                reason = "",
            )
        val out =
            json(
                run(
                    buildJsonObject { put("url", JsonPrimitive("http://example.com/api")) },
                ),
            )
        assertEquals("fetched", out.getValue("status").jsonPrimitive.content)
        assertEquals("http://example.com/api", out.getValue("finalUrl").jsonPrimitive.content)
        assertEquals(200L, out.getValue("httpStatus").jsonPrimitive.longOrNull)
        assertEquals("text/plain; charset=utf-8", out.getValue("contentType").jsonPrimitive.content)
        assertEquals("hello world", out.getValue("body").jsonPrimitive.content)
        assertEquals(11L, out.getValue("byteLength").jsonPrimitive.longOrNull)
        assertEquals(false, out.getValue("truncated").jsonPrimitive.boolean)
        assertEquals(1L, out.getValue("redirectCount").jsonPrimitive.longOrNull)
        assertEquals("", out.getValue("reason").jsonPrimitive.content)
        assertNoNulls(run(buildJsonObject { put("url", JsonPrimitive("http://example.com/api")) }))
    }

    @Test
    fun refusedIsAStableStatusNotAFakeSuccess() {
        bridge.outcome =
            HttpFetchOutcome(
                status = HttpFetchStatus.REFUSED,
                finalUrl = "",
                httpStatus = 0,
                contentType = "",
                body = "",
                bodyBytes = 0,
                truncated = false,
                redirectCount = 0,
                reason = "rebind-blocked",
            )
        val out =
            json(run(buildJsonObject { put("url", JsonPrimitive("http://evil.example")) }))
        assertEquals("refused", out.getValue("status").jsonPrimitive.content)
        assertEquals("", out.getValue("finalUrl").jsonPrimitive.content)
        assertEquals(0L, out.getValue("httpStatus").jsonPrimitive.longOrNull)
        assertEquals("", out.getValue("body").jsonPrimitive.content)
        assertEquals(0L, out.getValue("byteLength").jsonPrimitive.longOrNull)
        assertEquals("rebind-blocked", out.getValue("reason").jsonPrimitive.content)
        assertNoNulls(run(buildJsonObject { put("url", JsonPrimitive("http://evil.example")) }))
    }

    @Test
    fun timeoutMapsToTimedOut() {
        bridge.outcome =
            HttpFetchOutcome(
                status = HttpFetchStatus.TIMEOUT,
                finalUrl = "",
                httpStatus = 0,
                contentType = "",
                body = "",
                bodyBytes = 0,
                truncated = false,
                redirectCount = 0,
                reason = "connect timed out",
            )
        val result = run(buildJsonObject { put("url", JsonPrimitive("http://example.com")) })
        assertTrue("expected TimedOut, got $result", result is ToolExecutorResult.TimedOut)
    }

    @Test
    fun errorMapsToFailedWithBoundedReason() {
        bridge.outcome =
            HttpFetchOutcome(
                status = HttpFetchStatus.ERROR,
                finalUrl = "",
                httpStatus = 0,
                contentType = "",
                body = "",
                bodyBytes = 0,
                truncated = false,
                redirectCount = 0,
                reason = "connect failed",
            )
        val f = failed(run(buildJsonObject { put("url", JsonPrimitive("http://example.com")) }))
        assertEquals("connect failed", f.detail)
    }

    // ── argument validation (fail-closed, side-effect-free) ──────────────────────────────

    @Test
    fun missingUrlFails() {
        assertTrue(run(buildJsonObject {}) is ToolExecutorResult.Failed)
    }

    @Test
    fun blankUrlFails() {
        assertTrue(run(buildJsonObject { put("url", JsonPrimitive("   ")) }) is ToolExecutorResult.Failed)
    }

    @Test
    fun badMethodFails() {
        assertTrue(
            run(
                buildJsonObject {
                    put("url", JsonPrimitive("http://example.com"))
                    put("method", JsonPrimitive("POST"))
                },
            ) is ToolExecutorResult.Failed,
        )
    }

    // ── request the tool builds for the port ─────────────────────────────────────────────

    @Test
    fun defaultMethodIsGetAndDeadlinePropagates() {
        run(buildJsonObject { put("url", JsonPrimitive("http://example.com")) })
        val req = bridge.lastRequest ?: error("port was not called")
        assertEquals("GET", req.method)
        assertEquals("http://example.com", req.url)
        assertEquals(DEADLINE.toEpochMilli(), req.deadlineMillis)
        assertTrue(req.maxBodyBytes > 0)
    }

    @Test
    fun headMethodPassesThrough() {
        run(
            buildJsonObject {
                put("url", JsonPrimitive("http://example.com"))
                put("method", JsonPrimitive("HEAD"))
            },
        )
        assertEquals("HEAD", bridge.lastRequest?.method)
    }

    @Test
    fun cancelledAtTheBoundaryIsCancelled() {
        val cancel =
            object : CancelSignal {
                override fun isCancelled(): Boolean = true
            }
        val result =
            HttpFetchTool.executor(bridge).execute(
                ExecutableToolCall(
                    toolCallId = "call-1",
                    toolName = HttpFetchTool.NAME,
                    toolVersion = "1",
                    args = buildJsonObject { put("url", JsonPrimitive("http://example.com")) },
                    executionTarget = ExecutionTargetType.LOCAL_ANDROID,
                    deadline = DEADLINE,
                    cancel = cancel,
                ),
            )
        assertTrue(result is ToolExecutorResult.Cancelled)
    }

    // ── descriptor contract fields ───────────────────────────────────────────────────────

    @Test
    fun descriptorCarriesTheExpectedContract() {
        val d = HttpFetchTool.descriptor()
        assertEquals("http.fetch", d.name.value)
        assertEquals(1, d.version.value)
        assertEquals(RiskLevel.L1, d.baseRisk)
        assertEquals(ToolOperationClass.NETWORK, d.operationClass)
        assertEquals(30.seconds, d.timeout)
        assertTrue(d.maxOutputBytes > 0)
        assertEquals(Idempotency.NON_IDEMPOTENT, d.idempotency)
        assertEquals(ExecutionTargetType.LOCAL_ANDROID, d.executionTarget)
        assertTrue(d.origin is ToolOrigin.BuiltInOrigin)
        assertTrue(d.requiredCapabilities.isEmpty())
    }

    private class FakeHttpFetchBridge : HttpFetchBridge {
        var outcome: HttpFetchOutcome =
            HttpFetchOutcome(
                status = HttpFetchStatus.FETCHED,
                finalUrl = "http://example.com",
                httpStatus = 200,
                contentType = "text/plain",
                body = "ok",
                bodyBytes = 2,
                truncated = false,
                redirectCount = 0,
                reason = "",
            )

        var lastRequest: HttpFetchRequest? = null

        override fun fetch(request: HttpFetchRequest): HttpFetchOutcome {
            lastRequest = request
            return outcome
        }
    }

    private companion object {
        val DEADLINE: Instant = Instant.parse("2030-01-01T00:00:00Z")
    }
}
