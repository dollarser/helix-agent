package com.helix.feature.browser

import org.junit.Assert.assertThrows
import org.junit.Test

class BrowserSecuritySpecTest {
    @Test
    fun theDefaultSpecPassesTheHardeningGate() {
        BrowserSecuritySpec.assertHardened(BrowserSecuritySpec.DEFAULT)
    }

    @Test
    fun everySoftenedInvariantFailsTheGate() {
        for (relaxed in listOf(
            BrowserSecuritySpec.DEFAULT.copy(javaScriptEnabled = false),
            BrowserSecuritySpec.DEFAULT.copy(fileAccessEnabled = true),
            BrowserSecuritySpec.DEFAULT.copy(contentUrlAccessEnabled = true),
            BrowserSecuritySpec.DEFAULT.copy(fileAccessFromFileUrls = true),
            BrowserSecuritySpec.DEFAULT.copy(universalFileAccessFromFileUrls = true),
            BrowserSecuritySpec.DEFAULT.copy(mixedContentAllowed = true),
            BrowserSecuritySpec.DEFAULT.copy(safeBrowsingEnabled = false),
            BrowserSecuritySpec.DEFAULT.copy(domStorageEnabled = false),
            BrowserSecuritySpec.DEFAULT.copy(databaseEnabled = true),
            BrowserSecuritySpec.DEFAULT.copy(javaScriptCanOpenWindowsAutomatically = true),
        )) {
            assertThrows(IllegalArgumentException::class.java) { BrowserSecuritySpec.assertHardened(relaxed) }
        }
    }
}
