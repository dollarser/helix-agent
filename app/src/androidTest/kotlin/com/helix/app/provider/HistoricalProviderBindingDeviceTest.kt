package com.helix.app.provider

import com.helix.core.model.NormalizedEndpoint
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Room/service regression fixtures; compilation is not device acceptance. No real service/account. */
class HistoricalProviderBindingDeviceTest {
    @Test fun deletedSourceIsDiagnosedAfterRoomRestoresHistoricalSession() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { f ->
                f.storage.sessions.create("old", "Old", "p", "a", 1)
                f.status.recordPassed("p", 1, caps)
                f.service.delete("p")
                val restored = f.storage.sessions.resolve("old")
                assertNull(restored.providerId)
                assertEquals("a", restored.modelId)
                assertEquals(
                    ProviderBindingIssue.SOURCE_DELETED,
                    f.service.bindings.validate(restored.providerId, restored.modelId),
                )
                // Even a stale persisted passed outcome cannot revive a deleted source.
                f.status.recordPassed("p", 2, caps)
                assertEquals(ProviderBindingIssue.SOURCE_DELETED, f.service.bindings.validate("p", "a"))
                assertNull(f.service.bindings.validate(null, null))
            }
        }

    @Test fun updatedSourceKeepsExactBindingAndCanRecoverWithoutReselection() =
        runBlocking {
            ProviderModelsIntegrationDeviceTest.Fixture().use { f ->
                f.storage.sessions.create("old", "Old", "p", "a", 1)
                f.status.recordPassed("p", 1, caps)
                val config = f.service.storedConfig("p")
                f.service.update(
                    "p",
                    ProviderDraft(
                        templateId = null,
                        displayName = "Updated",
                        protocol = config.protocol,
                        endpoint = NormalizedEndpoint.parse("https://changed.test/v1"),
                        model = config.model,
                        headersJson = "{}",
                        credentialRequired = false,
                    ),
                    null,
                )
                val restored = f.storage.sessions.resolve("old")
                assertEquals("p", restored.providerId)
                assertEquals("a", restored.modelId)
                assertEquals(ProviderBindingIssue.CONFIGURATION_CHANGED, f.service.bindings.validate("p", "a"))
                // Explicit successful testing of the current configuration restores the same model.
                f.status.recordPassed("p", 2, caps)
                assertNull(f.service.bindings.validate("p", "a"))
            }
        }

    private val caps = ProviderCapabilities(true, true, false, false, false, false, 8192, CapabilitySource.PROBED)
}
