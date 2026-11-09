package com.helix.extensions.mobileuse.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredNodeTokensTest {
    @Test fun diagnosticsForgetOldTokensWithoutReactivatingThem() {
        val retired = RetiredNodeTokens()
        retired.remember(listOf("first"))
        val limit = NodeTokenRegistry.MAX_TOKENS * 2
        retired.remember((1..limit).map { "token-$it" })
        assertFalse("first" in retired)
        assertFalse("unknown" in retired)
        assertTrue("token-1" in retired)
        assertTrue("token-$limit" in retired)
        retired.remember(emptyList())
        assertTrue("token-$limit" in retired)
    }
}
