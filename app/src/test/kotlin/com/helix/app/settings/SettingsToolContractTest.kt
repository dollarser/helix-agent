package com.helix.app.settings

import com.helix.core.model.CancelSignal
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.ToolName
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import com.helix.tools.framework.ToolSchemaValidation
import com.helix.tools.framework.ToolSchemaValidator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SettingsToolContractTest {
    private fun call(
        args: String = "{}",
        cancelled: Boolean = false,
        expired: Boolean = false,
    ) = ExecutableToolCall(
        "call",
        "settings.inspect",
        "1",
        Json.parseToJsonElement(args) as JsonObject,
        ExecutionTargetType.LOCAL_ANDROID,
        if (expired) Instant.EPOCH else Instant.now().plusSeconds(30),
        object : CancelSignal {
            override fun isCancelled() = cancelled
        },
    )

    @Test fun registryHasOnlySeparatedInspectAndApplyEntries() {
        val registry = ToolRegistry()
        HelixSettingsTool.register(registry) { error("Registration must not access the app") }
        assertEquals(setOf("settings.inspect", "settings.apply"), registry.all().map { it.name.value }.toSet())
        assertNull(registry.resolveLatest(ToolName("helix.settings")))
        assertNull(registry.resolveLatest(ToolName("helix.settings.apply")))
    }

    @Test fun obsoleteProposalAndMutationArgsNeverReachApp() {
        val executor = HelixSettingsTool.executor { error("Invalid inspection must not access the app") }
        for (args in listOf("""{"action":"propose"}""", """{"mode":"ACT"}""", """{"page":"models"}""")) {
            assertTrue(executor.execute(call(args)) is ToolExecutorResult.Failed)
            assertTrue(
                ToolSchemaValidator.validate(
                    HelixSettingsTool.descriptor().inputSchema,
                    Json.parseToJsonElement(args),
                ) is ToolSchemaValidation.Invalid,
            )
        }
    }

    @Test fun staleAndCancelledInspectionNeverReachApp() {
        val executor = HelixSettingsTool.executor { error("Stale inspection must not access the app") }
        assertEquals(ToolExecutorResult.Cancelled, executor.execute(call(cancelled = true)))
        assertEquals(ToolExecutorResult.TimedOut, executor.execute(call(expired = true)))
    }

    @Test fun applyResultRequiresAppliedAndFutureInputBoundary() {
        assertEquals(
            ToolSchemaValidation.Valid,
            ToolSchemaValidator.validate(
                SettingsToolSchemas.applyOutput,
                Json.parseToJsonElement("""{"applied":true,"effectiveFor":"future_inputs"}"""),
            ),
        )
        assertTrue(
            ToolSchemaValidator.validate(
                SettingsToolSchemas.applyOutput,
                Json.parseToJsonElement("""{"applied":true,"effectiveFor":"current_turn"}"""),
            ) is ToolSchemaValidation.Invalid,
        )
    }
}
