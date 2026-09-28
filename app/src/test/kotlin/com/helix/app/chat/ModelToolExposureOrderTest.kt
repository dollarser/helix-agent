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

    @Test fun tokenActionsKeepTheirSnapshotAndNavigationContractsUnderCrowding() {
        val ui =
            listOf(
                "ui.snapshot",
                "ui.find",
                "ui.click",
                "ui.long_click",
                "ui.set_text",
                "ui.scroll",
                "ui.back",
                "ui.home",
                "ui.wait",
            ).map(::descriptor)
        val crowded = (0 until 80).map { descriptor("optional.$it") } + ui + descriptor("write")
        val exposed = ModelToolExposureOrder.prioritize(crowded, preferUi = true).take(ModelRequest.MAX_TOOLS)
        assertTrue(exposed.containsAll(ui))
        assertTrue(exposed.any { it.name.value == "write" })
        assertEquals(ModelRequest.MAX_TOOLS, exposed.size)
    }

    @Test fun priorityNeverReintroducesAnUnavailableContract() {
        val admitted = listOf(descriptor("ui.back"), descriptor("read"))
        assertEquals(admitted.toSet(), ModelToolExposureOrder.prioritize(admitted, preferUi = true).toSet())
    }

    @Test fun withoutAnActiveAutomationSessionUiDoesNotDisplaceOtherTools() {
        val tools = (0 until 64).map { descriptor("optional.$it") } + descriptor("ui.snapshot")
        assertEquals(tools.take(64), ModelToolExposureOrder.prioritize(tools).take(64))
    }

    @Test fun defaultSurfaceLeavesRoomForBothSmallCatalogAndLoadedWindow() {
        val names = ModelToolExposureOrder.defaultNames(preferUi = true)
        assertTrue(names.size + 2 * com.helix.app.mcp.McpToolDiscovery.WINDOW <= ModelRequest.MAX_TOOLS)
        assertTrue(names.containsAll(listOf("read", "write", "edit", "ui.snapshot", "skills.read")))
        assertTrue("code.linux.run" !in names)
        assertTrue("ui.snapshot" !in ModelToolExposureOrder.defaultNames(preferUi = false))
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
