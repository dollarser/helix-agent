package com.helix.app.provider

import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderResidence
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderSetupStepTest {
    private val capabilities =
        ProviderCapabilities(true, false, false, false, false, false, null, CapabilitySource.CONNECTION_ONLY)

    private fun row() =
        ProviderRowUi(
            id = "subscription-test",
            displayName = "Subscription",
            protocol = ProviderProtocol.OPENAI_RESPONSES,
            origin = "https://example.invalid",
            residence = ProviderResidence.PUBLIC_CLOUD,
            model = "m",
            hasKey = false,
            isCleartext = false,
            status = ConnectionTestStatus.Untested,
            capabilities = null,
            backendModels = null,
            templateNotes = emptyList(),
            provisioning = ProviderProvisioningKind.MANAGED_ACCOUNT,
            accountState =
                ManagedAccountSnapshot(
                    ManagedAccountSnapshot.State.LOGGED_IN,
                    "12345678-1234-1234-1234-123456789abc",
                ),
        )

    @Test fun loggedOutOrUnavailableAccountsAreSentToAccountManagement() {
        for (state in ManagedAccountSnapshot.State.entries.filter { it != ManagedAccountSnapshot.State.LOGGED_IN }) {
            val value = row().copy(accountState = ManagedAccountSnapshot(state))
            assertEquals(ProviderSetupStep.ACCOUNT, ProviderSetupStep.forRow(value))
            assertFalse(value.chatSelectable)
        }
    }

    @Test fun loggedInIsNotACompletedConnectionTest() {
        val value = row().copy(modelSelection = ProviderModelSelection(listOf("m"), true))
        assertEquals(ProviderSetupStep.CONNECTION, ProviderSetupStep.forRow(value))
        assertFalse(value.modelSelectable("m"))
    }

    @Test fun failedTestStillShowsTheConnectionStep() {
        val value = row().copy(status = ConnectionTestStatus.Failed(1, 1, ModelErrorCode.TRANSPORT, true))
        assertEquals(ProviderSetupStep.CONNECTION, ProviderSetupStep.forRow(value))
        assertFalse(value.chatSelectable)
    }

    @Test fun successfulConnectionWithNoSelectionShowsTheModelStep() {
        val value = row().copy(status = ConnectionTestStatus.Passed(1, capabilities))
        assertEquals(ProviderSetupStep.MODELS, ProviderSetupStep.forRow(value))
        assertFalse(value.offersConversationModels)
    }

    @Test fun successfulTestAndExplicitSelectionAreReadyWithoutChangingCapabilities() {
        val value =
            row().copy(
                status = ConnectionTestStatus.Passed(1, capabilities),
                modelSelection = ProviderModelSelection(listOf("m"), true),
            )
        assertEquals(ProviderSetupStep.READY, ProviderSetupStep.forRow(value))
        assertTrue(value.modelSelectable("m"))
        assertEquals(null, value.capabilities)
    }

    @Test fun knownModelFailureCannotBePresentedAsReadyJustBecauseConnectionPassed() {
        val value =
            row().copy(
                status = ConnectionTestStatus.Passed(1, capabilities),
                modelSelection = ProviderModelSelection(listOf("m"), true),
                modelGenerations = mapOf("m" to ProviderModelVerification(1, failure = ModelErrorCode.PROTOCOL)),
            )
        assertEquals(ProviderSetupStep.MODELS, ProviderSetupStep.forRow(value))
        assertFalse(value.modelSelectable("m"))
    }

    @Test fun accountRevocationOverridesAStoredPassedConnection() {
        val value =
            row().copy(
                status = ConnectionTestStatus.Passed(1, capabilities),
                accountState = ManagedAccountSnapshot(ManagedAccountSnapshot.State.LOGGED_OUT),
            )
        assertEquals(ProviderSetupStep.ACCOUNT, ProviderSetupStep.forRow(value))
        assertFalse(value.modelSelectable("m"))
    }

    @Test fun apiProviderDoesNotRequireAManagedAccount() {
        val value = row().copy(provisioning = ProviderProvisioningKind.USER_CONFIGURED, accountState = null)
        assertEquals(ProviderSetupStep.CONNECTION, ProviderSetupStep.forRow(value))
    }
}
