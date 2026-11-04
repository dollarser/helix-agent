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
