package com.helix.extensions.mobileuse.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetiredNodeTokensTest {
    @Test fun diagnosticsForgetOldTokensWithoutReactivatingThem() {
        val retired = RetiredNodeTokens()
        retired.remember(listOf("first"))
        retired.remember((1..400).map { "token-$it" })
        assertFalse("first" in retired)
        assertFalse("unknown" in retired)
        assertTrue("token-1" in retired)
        assertTrue("token-400" in retired)
        retired.remember(emptyList())
        assertTrue("token-400" in retired)
    }
}
