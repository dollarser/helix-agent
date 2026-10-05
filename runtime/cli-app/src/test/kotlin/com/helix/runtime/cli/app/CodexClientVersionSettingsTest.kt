package com.helix.runtime.cli.app

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexClientVersionSettingsTest {
    @Test fun absentOrMalformedStoredVersionUsesTheShippedDefault() {
        for (value in listOf(null, "", "https://evil.invalid", "0.1.0&other=x")) {
            assertEquals("0.160.0", CodexClientVersionSettings({ value }, { error("no write") }).current())
        }
    }

    @Test fun savedVersionSurvivesRecreationAndResetRemovesTheOverride() {
        var stored: String? = null

        fun settings() = CodexClientVersionSettings({ stored }, { stored = it })
        assertEquals("0.159.3", settings().save(" 0.159.3 "))
        assertEquals("0.159.3", settings().current())
        assertEquals("0.160.0", settings().reset())
        assertNull(stored)
        assertEquals("0.160.0", settings().current())
    }

    @Test fun invalidInputNeverReachesPersistence() {
        val settings = CodexClientVersionSettings({ "0.159.3" }, { error("invalid write") })
        for (value in listOf(
            "",
            "v0.159.3",
            "0.159",
            "01.2.3",
            "0.1.0?token=x",
            "0.1.0\r\nHeader:x",
            "0.1.0 & x",
            "9".repeat(70),
        )) {
            assertThrows(IllegalArgumentException::class.java) { settings.save(value) }
        }
        assertEquals("0.159.3", settings.current())
    }

    @Test fun explicitPrereleaseIsAllowedButNotSelectedByDefault() {
        assertTrue(CodexClientVersionSettings.valid("0.161.0-alpha.8"))
        assertEquals("0.160.0", CodexClientVersionSettings.DEFAULT)
    }

    @Test fun failedDurableSaveOrResetIsNotReportedAsSuccess() {
        val settings = CodexClientVersionSettings({ "0.158.0" }, { throw IllegalStateException("disk failure") })
        assertThrows(IllegalStateException::class.java) { settings.save("0.159.3") }
        assertThrows(IllegalStateException::class.java) { settings.reset() }
        assertEquals("0.158.0", settings.current())
    }

    @Test fun urlUsesOnlyThePinnedCatalogAndOneValidatedQueryParameter() {
        val url = CodexClientVersionSettings.catalogUrl("0.160.0").toHttpUrl()
        assertEquals("https", url.scheme)
        assertEquals("chatgpt.com", url.host)
        assertEquals("/backend-api/codex/models", url.encodedPath)
        assertEquals(setOf("client_version"), url.queryParameterNames)
        assertEquals("0.160.0", url.queryParameter("client_version"))
        assertThrows(IllegalArgumentException::class.java) { CodexClientVersionSettings.catalogUrl("0.1.0&model=x") }
    }
}
