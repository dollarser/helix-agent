package com.helix.app.plugin

import com.helix.extensions.plugin.PluginComponentKind
import com.helix.extensions.plugin.PluginComponentState
import kotlinx.serialization.json.Json
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
        val snapshot =
            PluginReadinessSnapshot(
                "plugin",
                7,
                true,
                true,
                listOf(fact, fact, offline),
                emptyList(),
            )
        val json = Json.parseToJsonElement(pluginRequestReadiness(listOf(snapshot), 1, false)).jsonObject
        val rows =
            json
                .getValue("plugins")
                .jsonArray
                .single()
                .jsonObject
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
        val host =
            json
                .getValue("browserHost")
                .jsonObject
                .getValue("state")
                .jsonPrimitive.content
        assertEquals("HOST_UNAVAILABLE", host)
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
                PluginReadinessSnapshot(
                    "plugin-$it",
                    1,
                    it == 63,
                    true,
                    List(1000) { component },
                    emptyList(),
                )
            }
        val text = pluginRequestReadiness(snapshots, 64, true)
        assertTrue(text.toByteArray().size <= MAX_PLUGIN_READINESS_BYTES)
        assertTrue(!text.contains("secret metadata"))
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals(
            "plugin-63",
            json
                .getValue("plugins")
                .jsonArray
                .first()
                .jsonObject
                .getValue("pluginId")
                .jsonPrimitive.content,
        )
        assertTrue(json.getValue("omittedPlugins").jsonPrimitive.int > 0)
        val huge = snapshots.last().copy(pluginId = "中文".repeat(10000))
        val omitted = Json.parseToJsonElement(pluginRequestReadiness(listOf(huge), 1, true)).jsonObject
        assertEquals(1, omitted.getValue("omittedSelectedPlugins").jsonPrimitive.int)
        assertTrue(omitted.getValue("plugins").jsonArray.isEmpty())
    }
}
