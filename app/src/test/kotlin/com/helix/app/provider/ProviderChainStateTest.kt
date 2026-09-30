package com.helix.app.provider

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.ModelErrorCode
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderResidence
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderChainStateTest {
    private val caps = ProviderCapabilities(true, true, false, false, false, false, 8192, CapabilitySource.PROBED)

    @Test fun failedBasicModelDoesNotDisableHealthySiblingOrMutatePreferences() {
        val backing = InMemoryLineStore()
        val states = ProviderTestStatusStore(backing)
        states.recordPassed("p", 1, caps, listOf("a", "b"))
        states.selectedModels.write("p", listOf("a", "b"))
        val failure = ProviderModelVerification(2, failure = ModelErrorCode.PROTOCOL)
        states.modelEvidence.generation("p", "endpoint", "a", failure)
        states.modelEvidence.generation("p", "endpoint", "b", ProviderModelVerification(3, caps))
        val current = row().copy(modelGenerations = states.modelEvidence.read("p", "endpoint").generations)
        assertTrue(current.chatSelectable)
        assertFalse(current.modelSelectable("a"))
        assertTrue(current.modelSelectable("b"))
        assertNull(current.capabilitiesForModel("a"))
        assertEquals(listOf("a", "b"), states.selectedModels.read("p"))
        assertTrue(states.statusFor("p") is ConnectionTestStatus.Passed)
    }

    @Test fun capabilityFailureIsNotBasicFailureAndConnectionDoesNotEraseIt() {
        val backing = InMemoryLineStore()
        val evidence = ProviderModelEvidenceStore(backing)
        evidence.verify("p", "url", "b", ProviderModelVerification(1, failure = ModelErrorCode.PROTOCOL))
        evidence.generation("p", "url", "b", ProviderModelVerification(2, caps.copy(toolCalls = false)))
        val saved = ProviderModelEvidenceStore(backing).read("p", "url")
        val current = row().copy(modelGenerations = saved.generations, modelVerifications = saved.verifications)
        assertTrue(current.modelSelectable("b"))
        assertNull(current.capabilitiesForModel("b"))
        assertEquals(ModelErrorCode.PROTOCOL, saved.verifications.getValue("b").failure)
    }

    @Test fun authenticatedCatalogDoesNotCreateGenerationEvidence() {
        val evidence = ProviderModelEvidenceStore(InMemoryLineStore())
        evidence.catalog("p", "url", listOf("a", "b"))
        assertTrue(evidence.read("p", "url").generations.isEmpty())
        assertTrue(evidence.read("p", "url").verifications.isEmpty())
    }

    @Test fun loginChangeInvalidatesEvidenceButTemporaryUnavailabilityDoesNotDeleteIt() {
        val first = ManagedAccountSnapshot(ManagedAccountSnapshot.State.LOGGED_IN, REVISION_A)
        val next = first.copy(revision = REVISION_B)
        val unavailable = ManagedAccountSnapshot(ManagedAccountSnapshot.State.UNAVAILABLE, REVISION_A)
        assertTrue(first.invalidates(ManagedAccountSnapshot(ManagedAccountSnapshot.State.UNKNOWN)))
        assertFalse(first.invalidates(first))
        assertFalse(unavailable.invalidates(first))
        assertFalse(first.invalidates(unavailable))
        assertTrue(next.invalidates(first))
        assertTrue(ManagedAccountSnapshot(ManagedAccountSnapshot.State.LOGGED_OUT).invalidates(first))
        assertFalse(row().copy(accountState = unavailable).chatSelectable)
    }

    @Test fun accountSnapshotReopensWithoutChangingModelPreferences() {
        val backing = InMemoryLineStore()
        val states = ProviderTestStatusStore(backing)
        states.selectedModels.write("p", listOf("b"))
        val login = ManagedAccountSnapshot(ManagedAccountSnapshot.State.LOGGED_IN, REVISION_A)
        states.accounts.save("p", login)
        states.clear("p")
        val reopened = ProviderTestStatusStore(backing)
        assertEquals(login, reopened.accounts.read("p"))
        assertEquals(listOf("b"), reopened.selectedModels.read("p"))
        assertEquals(2, backing.lines("provider-account-p").size)
        assertEquals(ManagedAccountSnapshot.State.UNKNOWN, reopened.accounts.read("missing").state)
    }

    private fun row() =
        ProviderRowUi(
            "p",
            "Source",
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            "https://example.test",
            ProviderResidence.PUBLIC_CLOUD,
            "a",
            false,
            false,
            ConnectionTestStatus.Passed(1, caps, listOf("a", "b")),
            caps,
            listOf("a", "b"),
            emptyList(),
        )

    private companion object {
        const val REVISION_A = "00000000-0000-0000-0000-000000000001"
        const val REVISION_B = "00000000-0000-0000-0000-000000000002"
    }
}
