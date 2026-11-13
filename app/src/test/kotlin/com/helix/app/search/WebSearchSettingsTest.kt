package com.helix.app.search

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.SecretAlias
import com.helix.core.storage.SecretStore
import com.helix.tools.android.WebSearchProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchSettingsTest {
    @Test
    fun nativeSearchOptInIsSeparateFromProviderCredentialsAndDefaultsOff() {
        val lines = InMemoryLineStore()
        val settings = WebSearchSettings(lines, MemorySecrets())
        assertFalse(settings.nativeOpenAiEnabled)
        settings.saveNativeOpenAi(true)
        assertTrue(WebSearchSettings(lines, MemorySecrets()).nativeOpenAiEnabled)
        assertNull(settings.configuredSearch())
        settings.saveNativeOpenAi(false)
        assertFalse(settings.nativeOpenAiEnabled)
    }

    @Test
    fun defaultsOffAndPersistsOnlyOptInOutsideSecretStore() {
        val lines = InMemoryLineStore()
        val settings = WebSearchSettings(lines, MemorySecrets())
        assertFalse(settings.enabled)
        assertNull(settings.configuredSearch()?.key)
        assertThrows(IllegalArgumentException::class.java) { settings.save(true, "") }
        settings.save(false, "synthetic-test-key")
        assertNull(settings.configuredSearch()?.key)
        settings.save(true, "")
        assertEquals("synthetic-test-key", settings.configuredSearch()?.key)
        assertEquals(listOf("TAVILY", "true"), lines.lines("configuration"))
        settings.removeKey()
        assertFalse(settings.enabled)
        assertFalse(settings.hasKey)
        assertNull(settings.configuredSearch()?.key)
    }

    @Test
    fun invalidKeyNeverEnablesOrStores() {
        val settings = WebSearchSettings(InMemoryLineStore(), MemorySecrets())
        assertThrows(IllegalArgumentException::class.java) { settings.save(true, "bad\nheader") }
        assertFalse(settings.enabled)
        assertFalse(settings.hasKey)
        settings.save(true, "synthetic-test-key")
        assertTrue(settings.enabled)
    }

    @Test
    fun providerKeysAreIsolatedAndNeverImplicitlyReused() {
        val settings = WebSearchSettings(InMemoryLineStore(), MemorySecrets())
        settings.save(true, "tavily-fixture")
        assertThrows(IllegalArgumentException::class.java) {
            settings.save(true, "", WebSearchProvider.DEEPSEEK)
        }
        assertEquals(WebSearchProvider.TAVILY, settings.provider)
        settings.save(false, "deepseek-fixture", WebSearchProvider.DEEPSEEK)
        assertNull(settings.configuredSearch())
        settings.save(true, "")
        assertEquals("deepseek-fixture", settings.configuredSearch()?.key)
        settings.removeKey()
        assertFalse(settings.hasKey(WebSearchProvider.DEEPSEEK))
        assertTrue(settings.hasKey(WebSearchProvider.TAVILY))
        settings.save(true, "", WebSearchProvider.TAVILY)
        assertEquals("tavily-fixture", settings.configuredSearch()?.key)
    }

    @Test
    fun unknownProviderCannotActivateSavedKey() {
        val lines = InMemoryLineStore()
        val settings = WebSearchSettings(lines, MemorySecrets())
        settings.save(true, "synthetic-test-key")
        lines.setLines("configuration", listOf("UNKNOWN", "true"))
        assertNull(settings.configuredSearch())
        assertFalse(settings.enabled)
    }

    @Test
    fun removingExplicitProviderCannotDeleteAnotherProvidersKey() {
        val lines = InMemoryLineStore()
        val secrets = MemorySecrets()
        val settings = WebSearchSettings(lines, secrets)
        val other = WebSearchSettings(lines, secrets)
        settings.save(true, "tavily-fixture", WebSearchProvider.TAVILY)
        other.save(true, "deepseek-fixture", WebSearchProvider.DEEPSEEK)
        settings.removeKey(WebSearchProvider.TAVILY)
        assertFalse(settings.hasKey(WebSearchProvider.TAVILY))
        assertTrue(settings.hasKey(WebSearchProvider.DEEPSEEK))
        assertNull(settings.configuredSearch())
    }

    private class MemorySecrets : SecretStore {
        private val data = mutableMapOf<SecretAlias, String>()

        override fun put(
            alias: SecretAlias,
            secret: String,
        ) {
            data[alias] = secret
        }

        override fun get(alias: SecretAlias): String = requireNotNull(data[alias])

        override fun delete(alias: SecretAlias) {
            data.remove(alias)
        }

        override fun contains(alias: SecretAlias): Boolean = data.containsKey(alias)

        override fun aliases(): Set<SecretAlias> = data.keys
    }
}
