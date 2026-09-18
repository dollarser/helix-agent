package com.helix.app.proot

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolOperationClass
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
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
