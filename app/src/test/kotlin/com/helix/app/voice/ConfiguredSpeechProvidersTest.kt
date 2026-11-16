package com.helix.app.voice

import com.helix.app.account.accountResponse
import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.SecretAlias
import com.helix.provider.api.CredentialLookup
import com.helix.provider.api.ProviderConfig
import com.helix.provider.api.wire.WireClient
import com.helix.provider.api.wire.WireRequest
import com.helix.provider.api.wire.WireResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfiguredSpeechProvidersTest {
    private fun config(host: String = "api.example.test") =
        ProviderConfig(
            "provider-fixture",
            "Fixture API",
            ProviderProtocol.OPENAI_RESPONSES,
            NormalizedEndpoint.parse("https://$host/v1"),
            "fixture-chat",
            emptyMap(),
            SecretAlias("fixture-key"),
            "{}",
        )

    @Test fun explicitProviderPinsDestinationAndResolvesKeyOnlyAtRequestTime() =
        runBlocking {
            var current = config().copy(headers = mapOf("content-type" to "application/json"))
            var lookups = 0
            val providers =
                ConfiguredSpeechProviders(
                    { listOf(current) },
                    { current },
                    CredentialLookup {
                        lookups++
                        "fixture-secret"
                    },
                )
            val option = providers.available().single()
            assertEquals(0, lookups)
            var sent: WireRequest? = null
            val transport =
                object : WireClient {
                    override suspend fun open(request: WireRequest): WireResponse {
                        sent = request
                        return accountResponse(200, "{}")
                    }
                }
            val wire = providers.wire(option.id, option.asrEndpoint, transport)
            val headers =
                mapOf(
                    "X-Client-ID" to "must-not-leak",
                    "Content-Type" to "multipart/form-data; boundary=generated-fixture",
                )
            val request = WireRequest("POST", option.asrEndpoint, headers, byteArrayOf())
            wire.open(request).body.close()
            assertEquals("Bearer fixture-secret", requireNotNull(sent).headers["Authorization"])
            assertFalse(requireNotNull(sent).headers.keys.any { it.equals("X-Client-ID", true) })
            val actualContentType = requireNotNull(sent).headers["Content-Type"]
            assertEquals("multipart/form-data; boundary=generated-fixture", actualContentType)
            assertEquals(1, requireNotNull(sent).headers.keys.count { it.equals("content-type", true) })
            assertEquals(1, lookups)
            current = config("changed.example.test")
            assertThrows(IllegalArgumentException::class.java) { runBlocking { wire.open(request) } }
            assertEquals(1, lookups)
        }

    @Test fun keyOnlyModelDoesNotRequireCatalogAndSettingsNeverCopyTheKey() {
        var requests = 0
        val providers =
            ConfiguredSpeechProviders(
                { error("No catalog is required to invoke a manually supplied model") },
                { config() },
                CredentialLookup { "fixture-secret" },
            )
        val lines = InMemoryLineStore()
        val settings =
            SpeechServiceSettings(
                httpEnabled = true,
                customAsrEndpoint = "https://api.example.test/v1/audio/transcriptions",
                customTtsEndpoint = "https://api.example.test/v1/audio/speech",
                asrModel = "manual-transcriber",
                providerId = "provider-fixture",
            )
        SpeechSettingsStore(lines, providers).save(settings)
        assertFalse(lines.lines("settings").joinToString().contains("fixture-secret"))
        val transport =
            object : WireClient {
                override suspend fun open(request: WireRequest): WireResponse {
                    requests++
                    assertEquals("POST", request.method)
                    assertTrue(requireNotNull(request.body).toString(Charsets.UTF_8).contains("manual-transcriber"))
                    return accountResponse(200, "{\"text\":\"ok\"}")
                }
            }
        val session = settings.session(settings.asrEndpoint, transport = transport, configuredProviders = providers)
        HttpFileAsr(session, settings.asrModel).use {
            it.send(floatArrayOf(0f))
            assertEquals("ok", it.finish())
        }
        assertEquals(1, requests)
    }

    @Test fun deletedProviderNeverFallsBackToAnonymousUpload() =
        runBlocking {
            var sent = false
            val credentials = CredentialLookup { error("not reached") }
            val providers = ConfiguredSpeechProviders({ emptyList() }, { error("deleted") }, credentials)
            val transport =
                object : WireClient {
                    override suspend fun open(request: WireRequest): WireResponse {
                        sent = true
                        return accountResponse(200, "{}")
                    }
                }
            val target = "https://api.example.test:443/v1/audio/transcriptions"
            assertThrows(IllegalStateException::class.java) {
                val wire = providers.wire("deleted", target, transport)
                runBlocking { wire.open(WireRequest("POST", target, emptyMap(), byteArrayOf())) }
            }
            assertFalse(sent)
        }

    @Test fun cooldownUsesMonotonicTimeAndDoesNotLeakBetweenScopes() {
        SpeechRetryCooldown.record("account-a|endpoint", 3000, 1_000_000)
        assertEquals(2000L, SpeechRetryCooldown.remaining("account-a|endpoint", 1_001_000_000))
        assertEquals(null, SpeechRetryCooldown.remaining("account-b|endpoint", 1_001_000_000))
        assertEquals(null, SpeechRetryCooldown.remaining("account-a|endpoint", 3_001_000_000))
    }

    @Test fun extendedCloudPlaybackDoesNotHitTheOldTwoMinuteLimit() {
        var frames = 0
        val engine =
            CloudSpeechEngine(
                object : CloudTtsConnection {
                    override fun stream(
                        text: String,
                        consume: (FloatArray, Int) -> Unit,
                    ) {
                        repeat(121) { consume(FloatArray(24000), 24000) }
                    }

                    override fun close() = Unit
                },
                maxDurationSeconds = 1200,
            )
        engine.use { it.generate("中文语音", { true }) { samples, _ -> frames += samples.size } }
        assertEquals(121 * 24000, frames)
    }

    @Test fun keyAuthenticatedStreamingSpeechKeepsOnlyBearerAuthentication() {
        val credentials = CredentialLookup { "fixture-secret" }
        val providers = ConfiguredSpeechProviders({ listOf(config()) }, { config() }, credentials)
        val settings =
            SpeechServiceSettings(
                httpEnabled = true,
                customTtsEndpoint = "https://api.example.test/v1/audio/speech",
                customAsrEndpoint = "https://api.example.test/v1/audio/transcriptions",
                providerId = "provider-fixture",
            )
        var calls = 0
        val wire =
            object : WireClient {
                override suspend fun open(request: WireRequest): WireResponse {
                    calls++
                    assertEquals("Bearer fixture-secret", request.headers["Authorization"])
                    assertFalse(request.headers.keys.any { it.equals("X-Client-ID", true) })
                    assertTrue(requireNotNull(request.body).toString(Charsets.UTF_8).contains("\"stream\":true"))
                    return accountResponse(200, "\u0000\u0000")
                }
            }
        val session = settings.session(settings.ttsEndpoint, transport = wire, configuredProviders = providers)
        HttpCloudTts(session).use { it.stream("synthetic") { _, rate -> assertEquals(24000, rate) } }
        assertEquals(1, calls)
    }
}
