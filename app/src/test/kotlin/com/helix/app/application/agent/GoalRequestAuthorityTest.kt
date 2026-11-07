package com.helix.app.application.agent

import com.helix.core.agent.RunControlConfig
import com.helix.core.model.AgentMode
import com.helix.core.model.GoalBudgets
import com.helix.core.model.TurnBudgets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class GoalRequestAuthorityTest {
    private val turns = mutableMapOf("m1" to "turn", "m2" to "turn", "foreign" to "other")
    private val requests = mutableMapOf<String, List<GoalUserRequest>>()
    private val authority = GoalRequestAuthority(requests, turns::get)

    @Test fun onlyTheExactQuotedHumanRequestSuppliesProviderAndBudgetAuthority() {
        val original = request("original", "m1", "provider-original", rounds = 3)
        val steering = request("keep working", "m2", "provider-steering", rounds = 7)
        requests["turn"] = listOf(original, steering)
        assertSame(steering, authority.authorizedRequest("session", "turn", "keep working"))
        assertEquals(
            7,
            authority
                .authorizedRequest("session", "turn", "keep working")
                ?.control
                ?.goalBudgets
                ?.maxGoalRounds,
        )
        assertEquals("provider-steering", authority.authorizedRequest("session", "turn", "keep working")?.providerId)
    }

    @Test fun missingWrongSessionForeignMessageAndUnquotedTextCannotAuthorize() {
        requests["turn"] = listOf(request("request", "foreign"))
        assertNull(authority.authorizedRequest("session", "turn", "request"))
        requests["turn"] = listOf(request("request", "m1"))
        assertNull(authority.authorizedRequest("other", "turn", "request"))
        assertNull(authority.authorizedRequest("session", "other", "request"))
        assertNull(authority.authorizedRequest("session", "turn", "model invented"))
        assertNull(authority.authorizedRequest("session", "turn", ""))
        turns.remove("m1")
        assertNull(authority.authorizedRequest("session", "turn", "request"))
    }

    @Test fun modelRequestRetainsTheOriginalAdmissionButDropsExcludedMessageAuthority() {
        val initial = request("initial direct request", null)
        requests["turn"] = listOf(initial, request("dropped", "m1"), request("retained", "m2"))
        authority.bindModelInputs("session", "turn", setOf("m2"), emptyList())
        assertSame(initial, authority.authorizedRequest("session", "turn", "initial direct request"))
        assertNull(authority.authorizedRequest("session", "turn", "dropped"))
        assertEquals("m2", authority.authorizedRequest("session", "turn", "retained")?.sourceMessageId)
    }

    @Test fun modelBoundInputsReplaceOlderEvidenceForTheSameMessage() {
        requests["turn"] = listOf(request("old", "m1"))
        val current = request("current", "m1").copy(providerSnapshot = "current-snapshot")
        authority.bindModelInputs("session", "turn", setOf("m1"), listOf(current))
        assertEquals(listOf(current), requests["turn"])
        assertNull(authority.authorizedRequest("session", "turn", "old"))
        assertSame(current, authority.authorizedRequest("session", "turn", "current"))
    }

    @Test fun unboundOrWrongSessionInputsFailBeforeReplacingAuthority() {
        val initial = request("initial", null)
        requests["turn"] = listOf(initial)
        assertThrows(IllegalArgumentException::class.java) {
            authority.bindModelInputs("session", "turn", emptySet(), listOf(request("missing", "m1")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            authority.bindModelInputs(
                "session",
                "turn",
                setOf("m1"),
                listOf(request("foreign", "m1").copy(sessionId = "other")),
            )
        }
        assertEquals(listOf(initial), requests["turn"])
    }

    @Test fun terminalReleaseAndGlobalStopSynchronouslyRevokeRequests() {
        requests["turn"] = listOf(request("first", null))
        requests["other-turn"] = listOf(request("second", null))
        authority.releaseTurn("turn")
        assertNull(authority.authorizedRequest("session", "turn", "first"))
        assertEquals("second", authority.authorizedRequest("session", "other-turn", "second")?.text)
        authority.clear()
        assertNull(authority.authorizedRequest("session", "other-turn", "second"))
    }

    private fun request(
        text: String,
        message: String?,
        provider: String = "provider",
        rounds: Int = 4,
    ) = GoalUserRequest(
        "session",
        text,
        provider,
        RunControlConfig(AgentMode.ACT, true, TurnBudgets(128), goalBudgets = GoalBudgets(rounds)),
        "snapshot-$provider",
        message,
    )
}
