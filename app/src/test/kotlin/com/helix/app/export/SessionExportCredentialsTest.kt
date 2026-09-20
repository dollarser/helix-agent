package com.helix.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionExportCredentialsTest {
    @Test fun credentialFieldsAreRemovedWithoutRemovingBusinessKeysOrTokenCounts() {
        val source =
            """{"headers":{"Authorization":"fixture-auth","Cookie":"fixture-cookie"},""" +
                """"inputTokens":42,"key":"business","data":"hello"}"""
        val result = SessionExportCredentials.sanitize(source, true)
        assertFalse(result.contains("fixture-auth"))
        assertFalse(result.contains("fixture-cookie"))
        assertTrue(result.contains("\"inputTokens\":42"))
        assertTrue(result.contains("\"key\":\"business\""))
        assertTrue(result.contains("hello"))
    }

    @Test fun ordinaryTextIsPreservedAndKnownCredentialsUseExistingPatterns() {
        assertEquals("hello 世界", SessionExportCredentials.sanitize("hello 世界", false))
        assertEquals("[注意] 普通业务说明", SessionExportCredentials.sanitize("[注意] 普通业务说明", false))
        assertEquals("{变量} 的解释", SessionExportCredentials.sanitize("{变量} 的解释", false))
        val result = SessionExportCredentials.sanitize("before sk-" + "x".repeat(20) + " after", false)
        assertEquals("before [redacted: credential] after", result)
        assertFalse(
            SessionExportCredentials
                .sanitize(
                    "-----BEGIN " + "PRIVATE KEY-----\nfixture-body",
                    false,
                ).contains("fixture-body"),
        )
    }

    @Test fun malformedAndExcessivelyNestedStructuredContentIsExplicitlyRedacted() {
        assertTrue(SessionExportCredentials.sanitize("{broken", true).contains("redacted"))
        assertTrue(SessionExportCredentials.sanitize("[".repeat(10000), true).contains("nesting"))
    }
}
