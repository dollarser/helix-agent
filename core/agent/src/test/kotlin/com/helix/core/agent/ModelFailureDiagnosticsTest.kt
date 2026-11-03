package com.helix.core.agent

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelFailureDiagnostic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ModelFailureDiagnosticsTest {
    @Test fun preservesAdapterFactsWhenProviderDisablesRetry() {
        val stream = ModelStreamState()
        stream.apply(
            ModelEvent.Error(
                ModelErrorCode.PROTOCOL,
                false,
                ModelFailureDiagnostic(
                    ModelFailureDiagnostic.Stage.HTTP,
                    ModelFailureDiagnostic.Reason.HTTP_STATUS,
                    httpStatus = 422,
                    adapterRetryable = true,
                ),
            ),
        )
        val result =
            Json
                .parseToJsonElement(
                    ModelFailureDiagnostics.record("PROTOCOL", stream, false, null, 0),
                ).jsonObject
        assertEquals("422", result.getValue("httpStatus").jsonPrimitive.content)
        assertEquals("true", result.getValue("adapterRetryable").jsonPrimitive.content)
        assertEquals("false", result.getValue("retryable").jsonPrimitive.content)
        assertEquals("PROVIDER_RETRY_DISABLED", result.getValue("recoveryDecision").jsonPrimitive.content)
    }

    @Test fun partialToolStreamExplainsWhyItCannotReplayWithoutLoggingArguments() {
        val stream = ModelStreamState()
        stream.apply(
            ModelEvent.ToolCallStarted(
                0,
                com.helix.core.model
                    .ToolCallId("call"),
                "write",
            ),
        )
        stream.apply(ModelEvent.ToolArgumentsDelta(0, "PRIVATE_SENTINEL"))
        stream.apply(ModelEvent.Error(ModelErrorCode.TRANSPORT, true))
        val result = ModelFailureDiagnostics.record("TRANSPORT", stream, true, null, 0)
        assertFalse(result.contains("PRIVATE_SENTINEL"))
        val data = Json.parseToJsonElement(result).jsonObject
        assertEquals("true", data.getValue("hasToolFragments").jsonPrimitive.content)
        assertEquals("PARTIAL_OR_TERMINAL_STREAM", data.getValue("recoveryDecision").jsonPrimitive.content)
    }

    @Test fun retryAndExhaustionAreSeparateFacts() {
        val stream = ModelStreamState()
        stream.apply(ModelEvent.Error(ModelErrorCode.TIMEOUT, true))
        val retry = Json.parseToJsonElement(ModelFailureDiagnostics.record("TIMEOUT", stream, true, 1000, 0)).jsonObject
        val end = Json.parseToJsonElement(ModelFailureDiagnostics.record("TIMEOUT", stream, true, null, 2)).jsonObject
        assertEquals("RETRY_SCHEDULED", retry.getValue("recoveryDecision").jsonPrimitive.content)
        assertEquals("TECHNICAL_RECOVERY_EXHAUSTED", end.getValue("recoveryDecision").jsonPrimitive.content)
        assertEquals("3", end.getValue("attempt").jsonPrimitive.content)
    }
}
