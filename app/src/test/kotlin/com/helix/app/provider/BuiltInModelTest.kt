package com.helix.app.provider

import com.helix.app.R
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.storage.entity.ProviderConfigEntity
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities
import com.helix.provider.api.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltInModelTest {
    @Test fun bundledDirectoryControlsCandidatesWithoutInventingInferenceEvidence() {
        val config = builtInConfig()
        val entity =
            ProviderConfigEntity(
                config.id,
                config.displayName,
                config.protocol.name,
                config.endpoint.full,
                config.model,
                "{}",
                null,
                config.capabilitySnapshot,
                provisioningKind = "BUNDLED_SERVICE",
                authKind = "NONE",
            )
        val undiscovered = providerRowUi(entity, ConnectionTestStatus.Untested)
        assertFalse(undiscovered.visibleInModelPicker)
        assertFalse(undiscovered.chatSelectable)
        val row =
            undiscovered.copy(
                builtInCatalog = BuiltInModelCatalog(1, listOf(BuiltInModel.MODEL_ID, "future-model")),
            )
        assertEquals(ConnectionTestStatus.Untested, row.status)
        assertTrue(row.chatSelectable)
        assertTrue(row.modelSelectable(BuiltInModel.MODEL_ID))
        assertFalse(row.modelSelectable("other"))
        assertTrue(row.modelSelectable("future-model"))
        assertFalse(row.capabilitiesForModel("future-model")!!.vision)
        assertFalse(row.capabilitiesForModel("future-model")!!.toolCalls)
        assertEquals(listOf(BuiltInModel.MODEL_ID, "future-model"), row.conversationModels)
        assertEquals(CapabilitySource.BUNDLED_CONTRACT, row.capabilities?.source)
        assertEquals(ProviderSetupStep.READY, ProviderSetupStep.forRow(row))
        assertFalse(ProviderProvisioningKind.BUNDLED_SERVICE in ProviderChannelPolicy.groups(false))
        assertTrue(ProviderChannelPolicy.permits("BUNDLED_SERVICE", false))
    }

    @Test fun configurationCannotRetargetOrBecomeUserConfigured() {
        val config = builtInConfig()
        val otherTarget = config.network.copy(endpoint = NormalizedEndpoint.parse("https://other.invalid/v1"))
        for (invalid in listOf(
            config.copy(id = "another"),
            config.copy(connection = config.connection.copy(provisioning = ProviderProvisioningKind.USER_CONFIGURED)),
            config.copy(connection = config.connection.copy(transport = otherTarget)),
        )) {
            assertThrows(IllegalArgumentException::class.java) { BuiltInModel.validate(invalid) }
        }
        BuiltInModel.validate(config.copy(model = "future-model"))
    }

    @Test fun terminalLabelsUseOriginalSnapshotInsteadOfCurrentModel() {
        val snapshot = ProviderTurnSnapshot.encode(builtInConfig(), null)
        assertEquals(R.string.builtin_model_auth_error, BuiltInModel.errorLabelForSnapshot("AUTH", snapshot))
        assertEquals(R.string.builtin_model_rate_limit, BuiltInModel.errorLabelForSnapshot("RATE_LIMITED", snapshot))
        assertEquals(R.string.builtin_model_service_error, BuiltInModel.errorLabelForSnapshot("SERVER_ERROR", snapshot))
        assertEquals(R.string.builtin_model_timeout, BuiltInModel.errorLabelForSnapshot("TIMEOUT", snapshot))
        assertNull(BuiltInModel.errorLabelForSnapshot("AUTH", snapshot.replace(BuiltInModel.PROVIDER_ID, "custom")))
        assertNull(BuiltInModel.errorLabelForSnapshot("AUTH", "broken"))
        assertNull(BuiltInModel.errorLabelForSnapshot("AUTH", null))
        val own =
            builtInConfig().copy(
                id = "custom",
                connection = BuiltInModel.connection.copy(provisioning = ProviderProvisioningKind.USER_CONFIGURED),
            )
        val ownSnapshot = ProviderTurnSnapshot.encode(own, null)
        assertNull(BuiltInModel.errorLabelForSnapshot("AUTH", ownSnapshot))
        assertFalse(ownSnapshot.contains("providerId"))
    }
}

internal fun builtInConfig() =
    ProviderConfig(
        BuiltInModel.PROVIDER_ID,
        "Helix",
        BuiltInModel.connection,
        BuiltInModel.MODEL_ID,
        emptyMap(),
        ProviderCapabilities.toJsonString(BuiltInModel.capabilities),
    )
