package com.helix.app.proot

import com.helix.app.approval.ApprovalUiMapper
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolOperationClass
import com.helix.runtime.proot.core.JobManifestEntry
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import com.helix.tools.framework.ToolExecutor
import com.helix.tools.framework.ToolExecutorResult
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DetachedJobToolsTest {
    @Test fun collectionLimitAccommodatesEscapedStreamsAndBoundedArtifactMetadata() {
        val entries =
            (0 until 64).map { JobManifestEntry("output/" + "界".repeat(500) + it, "a".repeat(64), 1) }
        val output =
            buildJsonObject {
                put("stdout", "\u0000".repeat(DetachedJobTools.MAX_STREAM_CHARACTERS))
                put("stderr", "\u0000".repeat(DetachedJobTools.MAX_STREAM_CHARACTERS))
                ProotArtifactReferences.summary("job_001122334455", entries).forEach { (key, value) -> put(key, value) }
            }
        assertTrue(output.toString().toByteArray(Charsets.UTF_8).size < DetachedJobTools.collect().maxOutputBytes)
    }

    @Test fun autoWaitDefaultsDoNotApplyToUnacceptedOrBackgroundLaunches() {
        val accepted = ToolExecutorResult.Completed(buildJsonObject { put("accepted", true) })
        val auto = call().copy(args = buildJsonObject { put("waitMode", "AUTO") })
        assertEquals(10_000L, DetachedJobWait.millis(auto, accepted))
        assertEquals(null, DetachedJobWait.millis(call(), accepted))
        assertEquals(null, DetachedJobWait.millis(auto, ToolExecutorResult.Completed(buildJsonObject {})))
    }

    @Test fun cancelledAutoWaitNeverCollectsOrClaimsTheJobStopped() {
        val cancelled =
            call().copy(
                cancel =
                    object : com.helix.tools.framework.CancelSignal {
                        override fun isCancelled() = true
                    },
            )
        val collect =
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall): ToolExecutorResult = error("must not collect")
            }
        val result =
            DetachedJobWait.finish(
                collect,
                cancelled,
                ToolExecutorResult.Completed(buildJsonObject { put("accepted", true) }),
                ToolExecutorResult.Completed(buildJsonObject { put("reason", "CONDITION_MET") }),
            ) as ToolExecutorResult.CancelledWithEffectTruth
        assertFalse(result.sideEffectFree)
        assertFalse(result.requiresReview)
    }

    @Test fun collectionFailurePreservesOriginalSideEffectTruth() {
        val collect =
            object : ToolExecutor {
                override fun execute(call: ExecutableToolCall) = ToolExecutorResult.Failed("IMPORT_FAILED", true, false)
            }
        val result =
            DetachedJobWait.finish(
                collect,
                call(),
                ToolExecutorResult.Completed(buildJsonObject { put("accepted", true) }),
                ToolExecutorResult.Completed(buildJsonObject { put("reason", "CONDITION_MET") }),
            ) as ToolExecutorResult.Failed
        assertFalse(result.sideEffectFree)
        assertFalse(result.requiresReview)
    }

    @Test fun backgroundJobApprovalShowsSharedNetworkForScriptAndArgv() {
        val descriptor = DetachedJobTools.start()
        assertEquals(2, descriptor.version.value)
        val script =
            requireNotNull(
                ApprovalUiMapper.codeExecutionUi(descriptor, buildJsonObject { put("script", "printf hello") }),
            )
        val argv =
            requireNotNull(
                ApprovalUiMapper.codeExecutionUi(
                    descriptor,
                    buildJsonObject { put("argv", buildJsonArray { add(JsonPrimitive("pwd")) }) },
                ),
            )
        assertTrue(script.online)
        assertTrue(argv.online)
        assertEquals("printf hello", script.code)
        assertEquals("pwd", argv.code)
    }

    @Test fun leaseSchemaIsSeparateFromTheSubmitDeadlineAndControlOnlyAcceptsOriginalCallId() {
        val start = DetachedJobTools.start()
        assertEquals("code.linux.job.start", start.name.value)
        assertEquals(ToolOperationClass.CODE_EXECUTION, start.operationClass)
        val properties = start.inputSchema.getValue("properties").jsonObject
        assertFalse(properties.containsKey("timeoutSeconds"))
        assertEquals(
            "1800",
            properties
                .getValue("leaseSeconds")
                .jsonObject
                .getValue("maximum")
                .jsonPrimitive.content,
        )
        assertEquals(ToolOperationClass.READ_ONLY, DetachedJobTools.control(false).operationClass)
        assertEquals(ToolOperationClass.LOCAL_MUTATION, DetachedJobTools.collect().operationClass)
        assertEquals(
            setOf("originalCallId"),
            DetachedJobTools
                .collect()
                .inputSchema
                .getValue("properties")
                .jsonObject.keys,
        )
        assertEquals(ToolOperationClass.LOCAL_MUTATION, DetachedJobTools.control(true).operationClass)
        assertEquals(
            setOf("originalCallId"),
            DetachedJobTools
                .control(false)
                .inputSchema
                .getValue("properties")
                .jsonObject.keys,
        )
    }

    @Test fun defaultLeaseCanOutliveTheSubmitCallWithoutChangingSynchronousTools() {
        val call = call()
        val before = System.currentTimeMillis()
        val detached = DetachedJobTools.parsed(call) as LinuxRunTool.ParsedResult.Ok
        assertTrue(detached.call.deadlineEpochMs >= before + 300_000)
        assertTrue(detached.call.deadlineEpochMs > call.deadline.toEpochMilli())
        val synchronous = LinuxRunTool.parsed(call) as LinuxRunTool.ParsedResult.Ok
        assertTrue(synchronous.call.deadlineEpochMs <= call.deadline.toEpochMilli())
    }

    @Test fun explicitLeaseIsBoundedAndAnExpiredSubmitCallCannotStart() {
        val requested = call(1_800)
        val before = System.currentTimeMillis()
        val parsed = DetachedJobTools.parsed(requested) as LinuxRunTool.ParsedResult.Ok
        assertTrue(parsed.call.deadlineEpochMs >= before + 1_800_000)
        val invalid = DetachedJobTools.parsed(call(1_801)) as LinuxRunTool.ParsedResult.ParseFailure
        assertTrue(invalid.detail.contains("leaseSeconds"))
        assertFalse(invalid.detail.contains("timeoutSeconds"))
        assertTrue(DetachedJobTools.parsed(call(0)) is LinuxRunTool.ParsedResult.ParseFailure)
        assertTrue(
            DetachedJobTools.parsed(call().copy(deadline = Instant.EPOCH)) is LinuxRunTool.ParsedResult.ParseFailure,
        )
    }

    private fun call(lease: Int? = null) =
        ExecutableToolCall(
            "call",
            DetachedJobTools.START,
            "1",
            buildJsonObject {
                put("script", "printf hello")
                if (lease != null) put("leaseSeconds", lease)
            },
            ExecutionTargetType.LOCAL_PROOT,
            Instant.now().plusSeconds(30),
            NoCancellation,
            "session",
            "turn",
        )
}
