package com.helix.tools.automation

import com.helix.core.model.ExecutionTargetType
import com.helix.core.policy.MobileUseGrantStore
import com.helix.tools.framework.CancelSignal
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.NoCancellation
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AutomationPrivilegedAdmissionTest {
    @Test fun originalAuthoritySurvivesServiceAbsenceButNotRevocationOrScopeChanges() {
        val records = mutableMapOf<String, List<String>>()
        val store = MobileUseGrantStore({ records[it].orEmpty() }, { k, v -> records[k] = v })
        val grant = store.authorize("chat", setOf("com.example.app"), false)
        val call =
            ExecutableToolCall(
                "call",
                "ui.click_match",
                "1",
                JsonObject(emptyMap()),
                ExecutionTargetType.LOCAL_ANDROID,
                Instant.now().plusSeconds(60),
                NoCancellation,
                "chat",
                "turn",
                authorizationScopeRef = grant.scope.toScopeRef(),
            )
        assertTrue(privilegedCallAdmitted(call, grant, "com.example.app", false))
        assertFalse(privilegedCallAdmitted(call, grant, "com.other.app", false))
        assertFalse(privilegedCallAdmitted(call.copy(sessionId = "other"), grant, "com.example.app", false))
        assertFalse(privilegedCallAdmitted(call, grant, "com.example.app", true))
        assertFalse(privilegedCallAdmitted(call.copy(deadline = Instant.EPOCH), grant, "com.example.app", false))
        assertFalse(
            privilegedCallAdmitted(
                call.copy(
                    cancel =
                        object : CancelSignal {
                            override fun isCancelled() = true
                        },
                ),
                grant,
                "com.example.app",
                false,
            ),
        )
        val replaced = store.authorize("chat", emptySet(), true)
        assertFalse(privilegedCallAdmitted(call, replaced, "com.example.app", false))
        store.revoke("chat")
        assertFalse(privilegedCallAdmitted(call, store.find("chat"), "com.example.app", false))
    }
}
