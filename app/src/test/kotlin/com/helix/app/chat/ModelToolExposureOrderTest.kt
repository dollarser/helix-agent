package com.helix.app.chat

import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ModelRequest
import com.helix.core.model.RiskLevel
import com.helix.core.model.ToolName
import com.helix.core.model.ToolOperationClass
import com.helix.core.model.ToolVersion
import com.helix.tools.framework.Idempotency
import com.helix.tools.framework.ToolDescriptor
import com.helix.tools.framework.ToolOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class ModelToolExposureOrderTest {
    @Test fun essentialBuiltInsSurviveTheModelToolLimit() {
        val optional = (0 until 80).map { descriptor("optional.$it") }
        val essential =
            listOf(
                descriptor("write"),
                descriptor("read"),
                descriptor("edit"),
                descriptor("files.list"),
                descriptor("files.stat"),
                descriptor("files.search"),
                descriptor("tools.search"),
                descriptor("goal.report"),
            )
        val exposed = ModelToolExposureOrder.prioritize(optional + essential).take(ModelRequest.MAX_TOOLS)
        val names = exposed.map { it.name.value }
        essential.forEach { assertTrue("${it.name.value} must survive truncation", it.name.value in names) }
        assertEquals(ModelRequest.MAX_TOOLS, exposed.size)
    }

    @Test fun optionalToolsKeepTheirOriginalRelativeOrder() {
        val tools = listOf(descriptor("optional.z"), descriptor("write"), descriptor("optional.a"))
        assertEquals(
            listOf("write", "optional.z", "optional.a"),
            ModelToolExposureOrder.prioritize(tools).map { it.name.value },
        )
    }

    private fun descriptor(name: String) =
        ToolDescriptor(
            name = ToolName(name),
            version = ToolVersion(1),
            description = name,
            inputSchema =
                kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                },
            outputSchema =
                kotlinx.serialization.json.buildJsonObject {
                    put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                },
            operationClass = ToolOperationClass.READ_ONLY,
            baseRisk = RiskLevel.L0,
            timeout = 1.seconds,
            maxOutputBytes = 1024,
            requiredCapabilities = emptySet(),
            idempotency = Idempotency.IDEMPOTENT,
            executionTarget = ExecutionTargetType.LOCAL_ANDROID,
            origin = ToolOrigin.BuiltInOrigin,
        )
}
