package com.helix.extensions.mobileuse.tools

import com.helix.extensions.mobileuse.automation.AutomationNodeBounds
import com.helix.extensions.mobileuse.automation.AutomationSnapshot
import com.helix.extensions.mobileuse.automation.AutomationSnapshotNode
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationSnapshotProjectionTest {
    @Test fun overlappingSameLabelTargetsExposeDifferentContextsWithoutPickingOne() {
        val directory = node("directory", "Podcasts")
        val selection = node("selection", "1 selected")
        val first = node("first", "More options").copy(parentToken = directory.token, depth = 1, clickable = true)
        val second = node("second", "More options").copy(parentToken = selection.token, depth = 1, clickable = true)
        val index = listOf(directory, first, selection, second).associateBy { it.token }
        val firstContext = AutomationNodeJson.full(first, index).getValue("ancestorContexts").jsonArray
        val secondContext = AutomationNodeJson.full(second, index).getValue("ancestorContexts").jsonArray
        assertEquals(
            "Podcasts",
            firstContext
                .single()
                .jsonObject
                .getValue("labels")
                .jsonArray
                .single()
                .jsonPrimitive.content,
        )
        assertEquals(
            "1 selected",
            secondContext
                .single()
                .jsonObject
                .getValue("labels")
                .jsonArray
                .single()
                .jsonPrimitive.content,
        )
        assertEquals(
            2,
            AutomationSnapshotProjection(snapshot(index.values.toList())).nodes.count {
                it["text"]?.jsonPrimitive?.content == "More options"
            },
        )
    }

    @Test fun containersDoNotConsumeTheOutputBudgetAndHiddenTargetsReceiveNoActionHint() {
        val hidden = node("hidden", "Secret menu").copy(visibleToUser = false, clickable = true, longClickable = true)
        val nodes = List(300) { node("container$it", null) } + hidden + node("late", "Visible action")
        val projection = AutomationSnapshotProjection(snapshot(nodes))
        assertFalse(projection.limited)
        assertEquals(listOf("late"), projection.nodes.map { it.getValue("token").jsonPrimitive.content })
        assertEquals("", automationClickTargetToken(hidden, mapOf(hidden.token to hidden)))
        assertFalse(AutomationNodeJson.full(hidden).containsKey("longClick"))
        assertEquals(
            "true",
            AutomationNodeJson
                .full(hidden)
                .getValue("offscreen")
                .jsonPrimitive.content,
        )
    }

    @Test fun largeStringsCannotExceedTheIndependentModelByteBudget() {
        val nodes =
            List(200) {
                node("node$it", "$it".padEnd(256, 'x')).copy(
                    contentDescription = "d".repeat(256),
                    viewId = "v".repeat(256),
                )
            }
        val projection = AutomationSnapshotProjection(snapshot(nodes))
        assertTrue(projection.limited)
        assertTrue(projection.nodes.size < 200)
        val bytes =
            kotlinx.serialization.json
                .JsonArray(projection.nodes)
                .toString()
                .toByteArray(Charsets.UTF_8)
                .size
        assertTrue(bytes <= AutomationSnapshotProjection.MAX_BYTES)
        assertFalse(snapshot(nodes).truncated)
    }

    private fun node(
        token: String,
        text: String?,
    ) = AutomationSnapshotNode(
        token,
        null,
        0,
        "View",
        text,
        null,
        null,
        AutomationNodeBounds(0, 0, 100, 100),
        false,
        false,
        false,
        false,
        true,
    )

    private fun snapshot(nodes: List<AutomationSnapshotNode>) =
        AutomationSnapshot("fixture", 1, 1, Instant.EPOCH, nodes, false)
}
