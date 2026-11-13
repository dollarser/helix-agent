package com.helix.app.application.tools

import com.helix.app.mcp.ToolDiscoveryWorkset
import com.helix.core.agent.ModelInputEstimate
import com.helix.core.agent.TokenEstimator
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ToolName
import com.helix.tools.files.ReadTool
import com.helix.tools.files.WriteTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolModelSchemaProjectionTest {
    @Test fun rawEagerCostWithMaximumLengthNameRemainsALowerBoundOfFinalProjection() {
        val descriptor =
            ReadTool.descriptor().copy(name = ToolName("${"a".repeat(64)}.${"b".repeat(63)}"))
        val raw =
            TokenEstimator.utf8Bytes(descriptor.name.value) +
                ModelInputEstimate.toolBytes(descriptor.description, descriptor.inputSchema.toString())
        assertEquals(128, descriptor.name.value.length)
        assertTrue(raw <= ToolDiscoveryWorkset.bytes(descriptor))
    }

    @Test fun exposureCostUsesTheExactPathAndPresentationProjectionWithoutChangingContracts() {
        for (descriptor in listOf(ReadTool.descriptor(), WriteTool.descriptor())) {
            val original = descriptor.inputSchema.toString()
            val projected = ToolModelSchemaProjection.project(descriptor)
            assertEquals(ToolPresentationMetadata.augment(FileToolArguments.modelSchema(descriptor)), projected)
            assertNull(projected.bindingRef)
            assertEquals(original, descriptor.inputSchema.toString())
            assertEquals(
                descriptor.inputSchema["required"],
                Json.parseToJsonElement(projected.inputSchemaJson).jsonObject["required"],
            )
            val raw = ModelToolSchema(descriptor.name, descriptor.description, original)
            assertTrue(ModelInputEstimate.toolBytes(projected) > ModelInputEstimate.toolBytes(raw))
            assertEquals(ModelInputEstimate.toolBytes(projected), ToolDiscoveryWorkset.bytes(descriptor))
            assertEquals(
                ModelInputEstimate.of(emptyList(), listOf(projected)).tools,
                TokenEstimator.estimateTokens(ToolDiscoveryWorkset.bytes(descriptor)),
            )
        }
    }
}
