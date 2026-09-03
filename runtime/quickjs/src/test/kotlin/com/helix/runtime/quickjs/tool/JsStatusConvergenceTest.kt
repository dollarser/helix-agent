package com.helix.runtime.quickjs.tool

import com.helix.core.model.ExecutionTargetType
import com.helix.runtime.quickjs.JsCancellation
import com.helix.runtime.quickjs.JsExecuteParams
import com.helix.runtime.quickjs.JsExecutionResult
import com.helix.runtime.quickjs.JsExecutionStatus
import com.helix.runtime.quickjs.JsHash
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.time.Instant

/**
 * HXA-054 (JVM): terminal-state convergence classification of the closed 11-status
 * [com.helix.runtime.quickjs.JsExecutionStatus] set onto the dispatcher's stable
 * ToolResult semantics, exercised through the REAL [CodeJavascriptRunTool.executor]
 * with a canned [JsExecutor] (the same injection point the production wiring uses —
 * the race-state-machine part that is testable on the JVM without a device):
 *
 * - only SUCCESS may map to a success ToolResult — no fake success from any status;
 * - the cancel convergence set {CANCELLED, INTERRUPTED} maps to stable, non-success
 *   outcomes that are never retried by the platform;
 * - CRASHED/UNKNOWN (outcome unknown after a process death or protocol anomaly) are
 *   NOT confirmed side-effect-free — the platform must never blindly retry them.
 */
class JsStatusConvergenceTest {
    private class CannedExecutor(
        private val status: JsExecutionStatus,
    ) : JsExecutor {
        override fun execute(
            params: JsExecuteParams,
            cancellation: JsCancellation?,
        ): JsExecutionResult {
            val outBytes =
                if (status == JsExecutionStatus.SUCCESS) {
                    """{"ok":true}""".toByteArray(StandardCharsets.UTF_8)
                } else {
                    ByteArray(0)
                }
            return JsExecutionResult(
                executionId = "exec-convergence",
                status = status,
                outputUtf8 = outBytes,
                outputBytes = outBytes.size.toLong(),
                outputSha256Hex = if (status == JsExecutionStatus.SUCCESS) JsHash.sha256Hex(outBytes) else "",
                inputSha256Hex = "",
                detail = "detail-$status",
                servicePid = if (status == JsExecutionStatus.SUCCESS) 4242 else -1,
                serviceUid = if (status == JsExecutionStatus.SUCCESS) 4242 else -1,
            )
        }
    }

    private fun call(): ExecutableToolCall =
        ExecutableToolCall(
            toolCallId = "call-convergence",
            toolName = CodeJavascriptRunTool.NAME,
            toolVersion = CodeJavascriptRunTool.VERSION.toString(),
            args = buildJsonObject { put("code", JsonPrimitive("return 1")) },
            executionTarget = ExecutionTargetType.LOCAL_QUICKJS,
            deadline = Instant.now().plusSeconds(60),
            cancel = NoCancellation,
        )

    @Test
    fun onlySuccessStatusYieldsASuccessToolResult() {
        for (status in JsExecutionStatus.CLOSED_SET) {
            val executor = CodeJavascriptRunTool.executor(CannedExecutor(status))
            val result = executor.execute(call())
            assertEquals(
                "status $status must map to a Completed ToolResult if and only if it is SUCCESS, got: $result",
                status == JsExecutionStatus.SUCCESS,
                result is ToolExecutorResult.Completed,
            )
        }
    }

    @Test
    fun cancelConvergenceSetMapsToStableNonSuccessOutcomes() {
        val cancelled = CodeJavascriptRunTool.executor(CannedExecutor(JsExecutionStatus.CANCELLED)).execute(call())
        assertEquals("CANCELLED must map to the dispatcher cancellation", ToolExecutorResult.Cancelled, cancelled)
        val interrupted =
            CodeJavascriptRunTool.executor(CannedExecutor(JsExecutionStatus.INTERRUPTED)).execute(call())
        assertTrue(
            "INTERRUPTED must map to a stable failure, got: $interrupted",
            interrupted is ToolExecutorResult.Failed,
        )
        assertTrue(
            "INTERRUPTED is confirmed side-effect-free (a bounded interrupt of a JS loop)",
            (interrupted as ToolExecutorResult.Failed).sideEffectFree,
        )
    }

    @Test
    fun crashedAndUnknownAreNotConfirmedSideEffectFree() {
        for (status in listOf(JsExecutionStatus.CRASHED, JsExecutionStatus.UNKNOWN)) {
            val result = CodeJavascriptRunTool.executor(CannedExecutor(status)).execute(call())
            assertTrue(
                "status $status must map to a stable failure, got: $result",
                result is ToolExecutorResult.Failed,
            )
            assertTrue(
                "$status means the outcome is unknown and must NOT be confirmed side-effect-free " +
                    "(no blind retry)",
                !(result as ToolExecutorResult.Failed).sideEffectFree,
            )
        }
    }
}
