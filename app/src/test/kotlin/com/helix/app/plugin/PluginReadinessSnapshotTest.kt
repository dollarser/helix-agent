package com.helix.app.plugin

import com.helix.extensions.plugin.PluginComponentKind
import com.helix.extensions.plugin.PluginComponentState
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PluginReadinessSnapshotTest {
    @Test fun contentAvailabilityDoesNotGrantInvocationEligibility() {
        val content =
            offline.copy(
                kind = PluginComponentKind.SKILL,
                state = PluginComponentState.AVAILABLE,
                loaded = true,
                requestAvailable = false,
            )
        assertEquals(PluginComponentState.NOT_SELECTED, content.requestState(false, true))
        assertEquals(PluginComponentState.REQUEST_UNAVAILABLE, content.requestState(true, true))
        assertEquals(PluginComponentState.DISABLED, content.requestState(true, false))
        assertEquals(PluginComponentState.AVAILABLE, content.copy(requestAvailable = true).requestState(true, true))
    }

    private val offline =
        PluginComponentFact(
            "server-a",
            PluginComponentKind.MCP,
            PluginComponentState.NOT_CONNECTED,
            true,
            false,
            "https://example.invalid",
            "account-a",
            PluginSetupReason.CONNECTION,
        )

    private fun snapshot(vararg facts: PluginComponentFact) =
        PluginReadinessSnapshot("package-a", 7, false, true, facts.toList(), emptyList())

    @Test fun savedIsNotLoadedAndUnselectedIsNotRequestReady() {
        val state = snapshot(offline)
        assertEquals(0, state.ready)
        assertEquals(PluginSetupReason.CONNECTION, state.setup)
        val facts =
            state
                .modelFacts()
                .getValue("components")
                .jsonArray
                .single()
                .jsonObject
        assertEquals("true", facts.getValue("saved").jsonPrimitive.content)
        assertEquals("false", facts.getValue("loaded").jsonPrimitive.content)
        assertEquals("NOT_SELECTED", facts.getValue("requestState").jsonPrimitive.content)
        assertEquals("account-a", facts.getValue("accountReference").jsonPrimitive.content)
        assertEquals(
            "NEXT_REQUEST",
            state
                .modelFacts()
                .getValue("effect")
                .jsonPrimitive.content,
        )
    }

    @Test fun usableSkillIsNotBlockedByAnUnconnectedServer() {
        val skill =
            offline.copy(
                ownerId = "skill-a",
                kind = PluginComponentKind.SKILL,
                state = PluginComponentState.AVAILABLE,
                loaded = true,
                setup = null,
            )
        val state = snapshot(skill, offline)
        assertEquals(1, state.ready)
        assertFalse(state.fullyReady)
        assertNull(state.setup)
        assertEquals(PluginComponentState.NOT_CONNECTED, state.components.last().state)
    }

    @Test fun changedConfigDoesNotPretendTheOldConnectionIsCurrent() {
        val state = snapshot(offline.copy(state = PluginComponentState.RELOAD_REQUIRED))
        assertEquals(0, state.ready)
        val selected = state.copy(selected = true).modelFacts()
        assertEquals(
            "RELOAD_REQUIRED",
            selected
                .getValue("components")
                .jsonArray
                .single()
                .jsonObject
                .getValue("requestState")
                .jsonPrimitive.content,
        )
    }
}
