package com.helix.runtime.cli.client

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CliAccountStateTest {
    @Test fun publicAccountStatesRoundTrip() {
        val expected =
            mapOf(
                "codex" to CliAccountState("LOGGED_IN", REVISION),
                "claude" to CliAccountState("LOGGED_OUT"),
                "grok" to CliAccountState("CREDENTIAL_ERROR"),
            )
        assertEquals(expected, CliAccountState.decode(CliAccountState.encode(expected)))
    }

    @Test fun malformedRevisionsUnknownProvidersAndExtraFieldsAreRejected() {
        val invalid =
            listOf(
                """{"codex":{"state":"LOGGED_IN","revision":null}}""",
                """{"codex":{"state":"LOGGED_IN","revision":"not-a-revision"}}""",
                """{"other":{"state":"LOGGED_OUT","revision":null}}""",
                """{"codex":{"state":"LOGGED_OUT","revision":null,"extra":"fixture"}}""",
            )
        invalid.forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { CliAccountState.decode(Json.parseToJsonElement(text)) }
        }
    }

    @Test fun missingAccountsInAnOlderStatusNeverInventsALogin() {
        val text = """{"protocolVersion":${CliRuntimeProtocol.VERSION},"runtimeVersion":"fixture","abi":"arm64-v8a",
            "lockSha256":"${"a".repeat(64)}","agentBackendState":"NOT_REGISTERED"}"""
        assertEquals(emptyMap<String, CliAccountState>(), CliRuntimeStatusCodec.decode(text).accounts)
    }

    private companion object {
        const val REVISION = "00000000-0000-0000-0000-000000000001"
    }
}
