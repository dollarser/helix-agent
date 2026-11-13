package com.helix.app.search

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.ExecutionTargetType
import com.helix.core.model.NoCancellation
import com.helix.core.model.SafetyProfile
import com.helix.core.model.ToolName
import com.helix.core.model.ToolVersion
import com.helix.core.storage.AndroidKeystoreSecretStore
import com.helix.tools.android.EgressPolicy
import com.helix.tools.android.EgressPolicyProvider
import com.helix.tools.android.WebSearchProvider
import com.helix.tools.android.WebSearchTool
import com.helix.tools.framework.ExecutableToolCall
import com.helix.tools.framework.ToolExecutorResult
import com.helix.tools.framework.ToolRegistry
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Offline fixture: no request is ever sent, and no production credential is touched. */
@RunWith(AndroidJUnit4::class)
class WebSearchSettingsDeviceTest {
    @Test
    fun encryptedCredentialAndDisabledExecutorAreTruthful() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val directory = java.io.File(context.cacheDir, "web-search-test-${java.util.UUID.randomUUID()}")
        val store =
            AndroidKeystoreSecretStore.create(
                object : android.content.ContextWrapper(context) {
                    override fun getFilesDir(): java.io.File = directory
                },
            )
        val settings = WebSearchSettings(InMemoryLineStore(), store)
        try {
            assertFalse(settings.nativeOpenAiEnabled)
            settings.saveNativeOpenAi(true)
            assertTrue(settings.nativeOpenAiEnabled)
            assertFalse(settings.enabled)
            settings.save(false, "synthetic-offline-fixture-key")
            assertNull(settings.configuredSearch()?.key)
            assertEncrypted(directory)
            settings.save(true, "")
            assertEquals("synthetic-offline-fixture-key", settings.configuredSearch()?.key)
            settings.save(false, "deepseek-offline-fixture", WebSearchProvider.DEEPSEEK)
            assertNull(settings.configuredSearch())
            settings.save(true, "")
            assertEquals("deepseek-offline-fixture", settings.configuredSearch()?.key)
            assertEquals(WebSearchProvider.DEEPSEEK, settings.configuredSearch()?.provider)
            settings.removeKey()
            assertTrue(settings.hasKey(WebSearchProvider.TAVILY))
            settings.save(false, "", WebSearchProvider.TAVILY)
            val registry = ToolRegistry()
            WebSearchTool.register(
                registry,
                object : EgressPolicyProvider {
                    override fun current() = EgressPolicy(SafetyProfile.STANDARD, emptySet())
                },
                settings,
            )
            val registered = requireNotNull(registry.resolveBinding(ToolName(WebSearchTool.NAME), ToolVersion(1)))
            val result =
                registered.executor.execute(
                    ExecutableToolCall(
                        "offline-search",
                        WebSearchTool.NAME,
                        "1",
                        buildJsonObject { put("query", "fixture") },
                        ExecutionTargetType.LOCAL_ANDROID,
                        Instant.now().plusSeconds(10),
                        NoCancellation,
                    ),
                )
            assertTrue(result is ToolExecutorResult.Failed)
            assertTrue(result.toString().contains("not-configured"))
            settings.removeKey()
            assertFalse(settings.hasKey)
            settings.saveNativeOpenAi(false)
            assertFalse(settings.nativeOpenAiEnabled)
        } finally {
            settings.removeKey()
            directory.deleteRecursively()
        }
    }

    private fun assertEncrypted(directory: java.io.File) {
        val encrypted =
            java.io
                .File(directory, "helix-secrets")
                .listFiles()
                .orEmpty()
        assertTrue(encrypted.isNotEmpty())
        assertTrue(
            encrypted.all {
                !it.readBytes().toString(Charsets.UTF_8).contains("synthetic-offline-fixture-key")
            },
        )
    }
}
