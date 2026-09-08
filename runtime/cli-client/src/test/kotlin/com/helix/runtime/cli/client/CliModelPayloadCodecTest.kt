package com.helix.runtime.cli.client

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ModelEvent
import com.helix.core.model.ModelMessage
import com.helix.core.model.ModelRequest
import com.helix.core.model.ModelRole
import com.helix.core.model.ModelToolSchema
import com.helix.core.model.ToolName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CliModelPayloadCodecTest {
    @Test fun providerEnvelopeSeparatesIdenticalModelsAndPreservesLegacyCodex() {
        val request = ModelRequest("shared-model", listOf(ModelMessage(ModelRole.USER, "hello")))
        val codex = CliModelRequestCodec.encode(request)
        assertEquals(CliModelProvider.CODEX, CliModelRequestCodec.decodeEnvelope(codex).provider)
        org.junit.Assert.assertFalse(codex.decodeToString().contains("providerId"))
        CliModelProvider.entries.filter { it != CliModelProvider.CODEX }.forEach { provider ->
            val bytes = CliModelRequestCodec.encode(request, provider)
            assertEquals(CliModelEnvelope(provider, request), CliModelRequestCodec.decodeEnvelope(bytes))
            org.junit.Assert.assertFalse(bytes.contentEquals(codex))
        }
        org.junit.Assert.assertFalse(
            CliModelRequestCodec
                .encode(request, CliModelProvider.CLAUDE)
                .contentEquals(CliModelRequestCodec.encode(request, CliModelProvider.COPILOT)),
        )
    }

    @Test fun unknownPlatformVersionAndLegacyInjectedPlatformAreRejected() {
        val request = ModelRequest("m", listOf(ModelMessage(ModelRole.USER, "hello")))
        val valid = CliModelRequestCodec.encode(request, CliModelProvider.CLAUDE).decodeToString()
        listOf(
            valid.replace("claude", "unknown"),
            valid.replace("\"version\":2", "\"version\":3"),
            valid.replace("\"version\":2", "\"version\":1"),
        ).forEach { forged ->
            assertThrows(
                RuntimeException::class.java,
            ) { CliModelRequestCodec.decodeEnvelope(forged.encodeToByteArray()) }
        }
    }

    @Test fun requestWithToolsRoundTrips() {
        val request =
            ModelRequest(
                model = "gpt-test",
                messages = listOf(ModelMessage(ModelRole.SYSTEM, "safe"), ModelMessage(ModelRole.USER, "hello")),
                tools = listOf(ModelToolSchema(ToolName("read"), "Read one item", """{"type":"object"}""")),
                maxOutputTokens = 100,
            )
        assertEquals(request, CliModelRequestCodec.decode(CliModelRequestCodec.encode(request)))
    }

    @Test fun unknownRequestFieldFailsClosed() {
        val valid =
            CliModelRequestCodec
                .encode(
                    ModelRequest("m", listOf(ModelMessage(ModelRole.USER, "x"))),
                ).decodeToString()
        val forged = valid.dropLast(1) + ",\"accessToken\":\"secret\"}"
        assertThrows(IllegalArgumentException::class.java) { CliModelRequestCodec.decode(forged.encodeToByteArray()) }
    }

    @Test fun eventsRoundTripWithOneTerminal() {
        val events =
            listOf<ModelEvent>(
                ModelEvent.TextDelta("hi"),
                ModelEvent.Usage(2, 1),
                ModelEvent.Completed("stop"),
            )
        assertEquals(events, CliModelEventCodec.decode(CliModelEventCodec.encode(events)))
    }

    @Test fun missingOrMultipleTerminalFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) {
            CliModelEventCodec.decode(CliModelEventCodec.encode(listOf(ModelEvent.TextDelta("x"))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CliModelEventCodec.decode(
                CliModelEventCodec.encode(
                    listOf(ModelEvent.Error(ModelErrorCode.TRANSPORT, true), ModelEvent.Completed("stop")),
                ),
            )
        }
    }
}
