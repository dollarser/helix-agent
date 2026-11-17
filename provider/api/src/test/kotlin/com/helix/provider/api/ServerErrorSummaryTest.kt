package com.helix.provider.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerErrorSummaryTest {
    @Test fun keepsActionableContextExplanationButNotCredentials() {
        val safe =
            ServerErrorSummary.redact(
                "Maximum context length is 32768 tokens; input has 27682. " +
                    "Authorization: Bearer short-token api_key=custom-key",
                listOf("custom-key"),
            )
        assertTrue(safe.contains("32768"))
        assertTrue(safe.contains("27682"))
        assertFalse(safe.contains("short-token"))
        assertFalse(safe.contains("custom-key"))
    }

    @Test fun structuredExceptionsNeverBypassKnownSecretsOrAllowArbitraryPaths() {
        val path = "tools[26].function.parameters"
        assertEquals(path, ServerErrorSummary.parameter(path, emptyList()))
        assertFalse(ServerErrorSummary.parameter(path, listOf("function")).contains("function"))
        assertFalse(ServerErrorSummary.code("invalid_function_parameters", listOf("function")).contains("function"))
        listOf("arbitrary_long_private_value", "tools[26].function.private_credential_value").forEach {
            assertFalse(ServerErrorSummary.parameter(it, emptyList()).contains("private"))
        }
        assertFalse(ServerErrorSummary.code("arbitrary_long_private_value", emptyList()).contains("private"))
        // A parameter-looking value in free text is not automatically considered safe.
        assertFalse(ServerErrorSummary.redact("password=$path", emptyList()).contains(path))
    }

    @Test fun unicodeAndUntrustedLongMessagesStayWithinTheUtf8Budget() {
        val safe = ServerErrorSummary.redact("错误原因。".repeat(1000), emptyList())
        assertTrue(safe.toByteArray().size <= 768)
        assertFalse(safe.contains('\uFFFD'))
        assertEquals(
            "[redacted: private key]",
            // Construct a synthetic marker, not a checked-in PEM credential.
            ServerErrorSummary.redact("-----BEGIN " + "PRIVATE KEY-----\nfixture", emptyList()),
        )
    }
}
