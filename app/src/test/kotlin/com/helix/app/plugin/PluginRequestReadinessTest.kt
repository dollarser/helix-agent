package com.helix.app.plugin

import com.helix.extensions.plugin.PluginComponentKind
import com.helix.extensions.plugin.PluginComponentState
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRequestReadinessTest {
    @Test fun requestProjectionPreservesUnavailableInvocationAndLiveConnectionFacts() {
        val fact =
            PluginComponentFact(
                "owner",
                PluginComponentKind.SKILL,
                PluginComponentState.AVAILABLE,
                saved = true,
                loaded = true,
                requestAvailable = false,
            )
        val offline = fact.copy(kind = PluginComponentKind.MCP, state = PluginComponentState.NOT_CONNECTED)
        val context =
            pluginReadinessContext(
                listOf(PluginReadinessSnapshot("plugin", 7, true, true, listOf(fact, fact, offline), emptyList())),
                1,
                false,
            )
        val rows =
            context.plugins
                .single()
                .requestSummary()
                .getValue("components")
                .jsonArray
        assertEquals(
            "REQUEST_UNAVAILABLE",
            rows[0]
                .jsonObject
                .getValue("requestState")
                .jsonPrimitive.content,
        )
        assertEquals(
            2,
            rows[0]
                .jsonObject
                .getValue("count")
                .jsonPrimitive.int,
        )
        assertEquals(
            "NOT_CONNECTED",
            rows[1]
                .jsonObject
                .getValue("requestState")
                .jsonPrimitive.content,
        )
        assertEquals(
            "HOST_UNAVAILABLE",
            context
                .acquisitionSummary()
                .getValue("browserHost")
                .jsonPrimitive.content,
        )
    }

    @Test fun unicodeAndManyComponentsStayBoundedWithExplicitOmissionsAndSelectedPriority() {
        val component =
            PluginComponentFact(
                "x".repeat(10000),
                PluginComponentKind.MCP,
                PluginComponentState.NOT_CONNECTED,
                true,
                false,
                "secret metadata".repeat(10000),
            )
        val snapshots =
            (0..63).map {
                PluginReadinessSnapshot("plugin-$it", 1, it == 63, true, List(1000) { component }, emptyList())
            }
        val context = pluginReadinessContext(snapshots, 64, true)
        val serialized = context.plugins.joinToString { it.requestSummary().toString() }
        assertTrue(serialized.toByteArray().size <= MAX_PLUGIN_READINESS_BYTES)
        assertTrue(!serialized.contains("secret metadata"))
        assertEquals("plugin-63", context.plugins.first().pluginId)
        assertTrue(context.omittedPlugins > 0)
        val huge = snapshots.last().copy(pluginId = "中文".repeat(10000))
        val omitted = pluginReadinessContext(listOf(huge), 1, true)
        assertEquals(1, omitted.omittedSelectedPlugins)
        assertTrue(omitted.plugins.isEmpty())
        assertTrue(context.acquisitionSummary().toString().contains("Individual optional rows"))
    }
}
