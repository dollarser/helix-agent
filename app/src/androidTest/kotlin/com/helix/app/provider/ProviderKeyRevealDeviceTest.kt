package com.helix.app.provider

import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real Room/Keystore boundary, synthetic credentials only; no network. */
class ProviderKeyRevealDeviceTest {
    @Test
    fun revealAndUnchangedSavePreserveStoredCredential() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { fixture ->
                val draft =
                    ProviderDraft(
                        templateId = null,
                        displayName = "Synthetic service",
                        protocol = ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        endpoint = NormalizedEndpoint.parse("https://example.test/v1"),
                        model = "model",
                        headersJson = "{}",
                        credentialRequired = false,
                    )
                val id = fixture.service.create(draft, "synthetic-fixture-secret")
                val original = fixture.service.storedConfig(id)
                assertEquals("synthetic-fixture-secret", fixture.service.revealUserConfiguredApiKey(id))
                assertEquals(original, fixture.service.storedConfig(id))
                fixture.service.update(id, draft, null)
                assertEquals("synthetic-fixture-secret", fixture.service.revealUserConfiguredApiKey(id))
                assertEquals(original.auth, fixture.service.storedConfig(id).auth)
                fixture.service.update(id, draft, "synthetic-replacement")
                assertEquals("synthetic-replacement", fixture.service.revealUserConfiguredApiKey(id))
                fixture.service.delete(id)
            }
        }

    @Test
    fun absentKeyReturnsNullAndProtectedSourcesAreRejected() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { fixture ->
                assertNull(fixture.service.revealUserConfiguredApiKey("p"))
                assertTrue(
                    runCatching {
                        fixture.service.revealUserConfiguredApiKey(BuiltInModel.PROVIDER_ID)
                    }.exceptionOrNull() is IllegalArgumentException,
                )
            }
            ProviderModelsIntegrationDeviceTest.Fixture(managed = true).use { fixture ->
                assertTrue(
                    runCatching {
                        fixture.service.revealUserConfiguredApiKey("p")
                    }.exceptionOrNull() is IllegalArgumentException,
                )
            }
        }
}
