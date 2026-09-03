package com.helix.runtime.quickjs.tool

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolOperationClass
import com.helix.runtime.quickjs.JsCancellation
import com.helix.runtime.quickjs.JsExecuteParams
import com.helix.runtime.quickjs.JsExecutionLimits
import com.helix.runtime.quickjs.JsExecutionResult
import com.helix.runtime.quickjs.JsExecutionStatus
import com.helix.runtime.quickjs.JsHash
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolOrigin
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * HXA-053 (JVM): the `code.javascript.run` descriptor contract, the executor's 11-status mapping
 * to stable ToolResult semantics, the redacted §4.8 audit block, and the Plan-mode exclusion.
 * The execution seam is a fake [JsExecutor] that returns a canned [JsExecutionResult] per status,
 * so no Android/Parcel surface is touched.
 */
class CodeJavascriptRunToolTest {
    private class CapturingExecutor(
        var result: JsExecutionResult,
    ) : JsExecutor {
        var lastParams: JsExecuteParams? = null
        var lastCancellation: JsCancellation? = null

        override fun execute(
            params: JsExecuteParams,
            cancellation: JsCancellation?,
        ): JsExecutionResult {
            lastParams = params
            lastCancellation = cancellation
            return result
        }
    }

    private fun jsResult(
        status: JsExecutionStatus,
        output: String = "",
        detail: String = "",
        isolated: Boolean = false,
    ): JsExecutionResult {
        val outBytes = output.toByteArray(StandardCharsets.UTF_8)
        return JsExecutionResult(
            executionId = "exec-1",
            status = status,
            outputUtf8 = outBytes,
            outputBytes = outBytes.size.toLong(),
            outputSha256Hex = if (status == JsExecutionStatus.SUCCESS) JsHash.sha256Hex(outBytes) else "",
            inputSha256Hex = "",
            detail = detail,
            servicePid = if (isolated) 4242 else -1,
            serviceUid = if (isolated) 4242 else -1,
        )
    }

    private fun successArgs(): JsonObject =
        buildJsonObject {
            put("code", JsonPrimitive("return { doubled: input.n * 2 }"))
            put("input", buildJsonObject { put("n", JsonPrimitive(21)) })
        }

    private fun call(args: JsonObject) =
        ExecutableToolCall(
            toolCallId = "call-1",
            toolName = CodeJavascriptRunTool.NAME,
            toolVersion = CodeJavascriptRunTool.VERSION.toString(),
            args = args,
            executionTarget = ExecutionTargetType.LOCAL_QUICKJS,
            deadline = Instant.now().plusSeconds(60),
            cancel = NoCancellation,
        )

    // ---------------------------------------------------------------- descriptor contract

    @Test
    fun descriptorIsL2CodeExecutionOnTheQuickJsLane() {
        val d = CodeJavascriptRunTool.descriptor()
        assertEquals("code.javascript.run", d.name.value)
        assertEquals(1, d.version.value)
        assertEquals(ToolOperationClass.CODE_EXECUTION, d.operationClass)
        assertEquals(RiskLevel.L2, d.baseRisk)
        assertEquals(ExecutionTargetType.LOCAL_QUICKJS, d.executionTarget)
        assertEquals(Idempotency.NON_IDEMPOTENT, d.idempotency)
        assertTrue("a JS run needs no OS capability gate", d.requiredCapabilities.isEmpty())
        assertTrue(d.origin is ToolOrigin.BuiltInOrigin)
        // 64-hex schema hash, deterministic across constructions.
        assertTrue(d.schemaHash.hex.length == 64)
        assertEquals(CodeJavascriptRunTool.descriptor().schemaHash.hex, d.schemaHash.hex)
        assertEquals(CodeJavascriptRunTool.descriptor().contractHash.hex, d.contractHash.hex)
    }

    @Test
    fun inputSchemaExposesOnlyBusinessInputsNoLimits() {
        val props = CodeJavascriptRunTool.descriptor().inputSchema["properties"]?.jsonObject
        assertNotNull("input schema must declare properties", props)
        // ONLY the model-visible business inputs — NO limit/timeout/memory/source/output param.
        assertEquals(setOf("code", "input"), props!!.keys)
        val codeSchema = props["code"]?.jsonObject
        assertEquals("string", codeSchema?.get("type")?.jsonPrimitive?.content)
        // The code is bounded; `input` is the optional JSON value (a type union, no size param).
        assertNotNull(codeSchema?.get("maxLength"))
        val required = CodeJavascriptRunTool.descriptor().inputSchema["required"]?.jsonArray
        assertEquals(listOf("code"), required?.map { it.jsonPrimitive.content })
        // No additional properties: a model-supplied "timeout"/"memoryBytes" is schema-invalid.
        assertEquals(
            false,
            CodeJavascriptRunTool
                .descriptor()
                .inputSchema["additionalProperties"]
                ?.jsonPrimitive
                ?.booleanOrNull,
        )
    }

    // ---------------------------------------------------------------- executor: limits are fixed

    @Test
    fun executorAppliesFixedDefaultLimitsRegardlessOfArgs() {
        val runner = CapturingExecutor(jsResult(JsExecutionStatus.SUCCESS, output = "null", isolated = true))
        val executor = CodeJavascriptRunTool.executor(runner)
        executor.execute(call(successArgs()))
        val params = runner.lastParams!!
        assertEquals(JsExecutionLimits.DEFAULTS, params.limits)
        // Source is the model's `code`; input is canonicalized; execution id is non-blank + fresh.
        assertEquals("return { doubled: input.n * 2 }", params.source)
        assertNotNull(params.inputJsonUtf8)
        assertTrue(params.executionId.isNotBlank())
    }

    @Test
    fun executorUsesNoInputWhenInputArgumentAbsent() {
        val runner =
            CapturingExecutor(jsResult(JsExecutionStatus.SUCCESS, output = "42", isolated = true))
        val executor = CodeJavascriptRunTool.executor(runner)
        executor.execute(
            call(
                buildJsonObject { put("code", JsonPrimitive("return 1 + 1")) },
            ),
        )
        assertNull("absent input must mean no input (null payload)", runner.lastParams?.inputJsonUtf8)
    }

    // ---------------------------------------------------------------- 11-status mapping

    @Test
    fun successBackfillsBoundedResultWithAuditBlock() {
        val outDoc = """{"doubled":42}"""
        val runner = CapturingExecutor(jsResult(JsExecutionStatus.SUCCESS, output = outDoc, isolated = true))
        val executor = CodeJavascriptRunTool.executor(runner)
        val result = executor.execute(call(successArgs()))
        val completed = result as ToolExecutorResult.Completed
        val out = completed.output.jsonObject
        assertEquals(outDoc, out["result"]?.jsonPrimitive?.content)
        assertEquals(
            outDoc.toByteArray(StandardCharsets.UTF_8).size.toLong(),
            out["outputBytes"]?.jsonPrimitive?.longOrNull,
        )
        // The §4.8 audit block is present, redacted, and correct.
        val audit = completed.auditDetail!!.jsonObject
        assertEquals("SUCCESS", audit["status"]?.jsonPrimitive?.content)
        assertEquals(
            JsHash.sha256Utf8("return { doubled: input.n * 2 }"),
            audit["sourceSha256"]?.jsonPrimitive?.content,
        )
        assertTrue((audit["outputBytes"]?.jsonPrimitive?.longOrNull ?: -1L) > 0L)
        assertEquals(
            JsHash.sha256Hex(outDoc.toByteArray(StandardCharsets.UTF_8)),
            audit["outputSha256"]?.jsonPrimitive?.content,
        )
        assertTrue(audit["inputSha256"]?.jsonPrimitive?.content!!.isNotEmpty())
        assertEquals("true", audit["isolated"]?.jsonPrimitive?.content)
        val limits = audit["limits"]?.jsonObject!!
        assertEquals(JsExecutionLimits.DEFAULT_TIMEOUT_MS.toString(), limits["timeoutMs"]?.jsonPrimitive?.content)
        assertEquals(JsExecutionLimits.DEFAULT_MEMORY_BYTES.toString(), limits["memoryBytes"]?.jsonPrimitive?.content)
        assertEquals(
            JsExecutionLimits.DEFAULT_MAX_OUTPUT_BYTES.toString(),
            limits["maxOutputBytes"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun timeoutMapsToDispatcherTimeout() {
        val executor = CodeJavascriptRunTool.executor(CapturingExecutor(jsResult(JsExecutionStatus.TIMEOUT)))
        assertTrue(executor.execute(call(successArgs())) is ToolExecutorResult.TimedOut)
    }

    @Test
    fun cancelledMapsToDispatcherCancelled() {
        val executor = CodeJavascriptRunTool.executor(CapturingExecutor(jsResult(JsExecutionStatus.CANCELLED)))
        assertTrue(executor.execute(call(successArgs())) is ToolExecutorResult.Cancelled)
    }

    @Test
    fun inFlightCancelSignalIsForwardedToTheBackend() {
        val runner = CapturingExecutor(jsResult(JsExecutionStatus.INTERRUPTED))
        val executor = CodeJavascriptRunTool.executor(runner)
        executor.execute(call(successArgs()))
        assertNotNull("the dispatcher cancel signal must be wired into the backend seam", runner.lastCancellation)
    }

    private fun assertFailed(
        status: JsExecutionStatus,
        detail: String = "",
        isolated: Boolean = false,
        expectSideEffectFree: Boolean? = null,
    ) {
        val runner = CapturingExecutor(jsResult(status, detail = detail, isolated = isolated))
        val executor = CodeJavascriptRunTool.executor(runner)
        val result = executor.execute(call(successArgs()))
        val failed = result as? ToolExecutorResult.Failed
        assertNotNull("status $status must map to a stable Failed, was: ${result::class.java.name}", failed)
        expectSideEffectFree?.let { assertEquals("$status sideEffectFree", it, failed!!.sideEffectFree) }
        // Every non-success maps to the matching terminal status in the redacted audit block.
        assertEquals(
            status.name,
            failed!!
                .auditDetail
                ?.get("status")
                ?.jsonPrimitive
                ?.content,
        )
        // The model-visible detail never leaks the raw code body.
        assertFalse("detail must not contain the code", failed.detail.contains("doubled: input.n"))
    }

    @Test
    fun nonSuccessStatusesMapToStableFailures() {
        assertFailed(JsExecutionStatus.INTERRUPTED, isolated = true, expectSideEffectFree = true)
        assertFailed(JsExecutionStatus.OOM, isolated = true, expectSideEffectFree = true)
        assertFailed(
            JsExecutionStatus.JS_ERROR,
            detail = "helixMain threw: TypeError: boom",
            isolated = true,
            expectSideEffectFree = true,
        )
        assertFailed(JsExecutionStatus.OUTPUT_LIMIT, isolated = true, expectSideEffectFree = true)
        assertFailed(JsExecutionStatus.CRASHED, isolated = true, expectSideEffectFree = false)
        assertFailed(
            JsExecutionStatus.REQUEST_REJECTED,
            detail = "source exceeds maxSourceBytes 262144",
            expectSideEffectFree = true,
        )
        assertFailed(JsExecutionStatus.BIND_FAILED, expectSideEffectFree = true)
        assertFailed(JsExecutionStatus.UNKNOWN, expectSideEffectFree = false)
    }

    @Test
    fun jsErrorDetailIsBoundedButKept() {
        val longDetail = "helixMain threw: " + "x".repeat(2000)
        val runner = CapturingExecutor(jsResult(JsExecutionStatus.JS_ERROR, detail = longDetail, isolated = true))
        val result = CodeJavascriptRunTool.executor(runner).execute(call(successArgs()))
        val failed = result as ToolExecutorResult.Failed
        assertTrue("bounded detail must be shorter than the raw 2000+ char engine text", failed.detail.length < 2000)
        assertTrue(failed.detail.startsWith("JavaScript error: helixMain threw:"))
    }

    // ---------------------------------------------------------------- Plan-mode exclusion

    @Test
    fun codeExecutionToolIsExcludedFromTheReadOnlyPlanView() {
        val registry = ToolRegistry().also { it.register(CodeJavascriptRunTool.descriptor()) }
        val planView = registry.visibleFor(setOf(ToolOperationClass.READ_ONLY))
        assertTrue(
            "a CODE_EXECUTION tool must never appear in the Plan (READ_ONLY) tool table",
            planView.none { it.name.value == CodeJavascriptRunTool.NAME },
        )
        // But it IS present in the full registered set (Act/Goal modes see it).
        assertTrue(registry.all().any { it.name.value == CodeJavascriptRunTool.NAME })
    }

    // ---------------------------------------------------------------- audit redaction

    @Test
    fun auditBlockCarriesNoCodeOrInputBody() {
        val distinctiveCode = "return hxadevRedactionMarkerAlpha(input)"
        val distinctiveInput = "hxadevRedactionMarkerBeta"
        val args =
            buildJsonObject {
                put("code", JsonPrimitive(distinctiveCode))
                put("input", JsonPrimitive(distinctiveInput))
            }
        val runner =
            CapturingExecutor(jsResult(JsExecutionStatus.SUCCESS, output = "null", isolated = true))
        val result = CodeJavascriptRunTool.executor(runner).execute(call(args))
        val audit = (result as ToolExecutorResult.Completed).auditDetail!!.jsonObject
        val flat = allStringValues(audit).joinToString("|")
        // The exact code source and the input body NEVER enter the audit block (hashes only).
        assertFalse("audit must not contain the code body", flat.contains("hxadevRedactionMarkerAlpha"))
        assertFalse("audit must not contain the input body", flat.contains("hxadevRedactionMarkerBeta"))
        // Only the hash / size / limit / status keys are present (allowlisted by construction).
        assertEquals(
            setOf(
                "status",
                "sourceSha256",
                "sourceBytes",
                "inputBytes",
                "inputSha256",
                "outputBytes",
                "outputSha256",
                "limits",
                "isolated",
            ),
            audit.keys,
        )
        // `input` is a JSON array of allowed types in the schema (guards the union shape).
        val inputType =
            CodeJavascriptRunTool
                .descriptor()
                .inputSchema["properties"]
                ?.jsonObject
                ?.get(
                    "input",
                )?.jsonObject
                ?.get("type")
        assertTrue(inputType is JsonArray)
    }

    private fun allStringValues(element: kotlinx.serialization.json.JsonElement): List<String> =
        when (element) {
            is JsonPrimitive -> element.takeIf { it.isString }?.content?.let { listOf(it) } ?: emptyList()
            is JsonObject -> element.values.flatMap { allStringValues(it) }
            is JsonArray -> element.flatMap { allStringValues(it) }
            kotlinx.serialization.json.JsonNull -> emptyList()
        }
}
