package com.helix.app.provider

import com.helix.app.internal.InMemoryLineStore
import com.helix.core.model.ProviderProtocol
import com.helix.core.model.ProviderProvisioningKind
import com.helix.core.model.ProviderResidence
import com.helix.provider.api.CapabilitySource
import com.helix.provider.api.ProviderCapabilities
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelSelectionTest {
    @Test fun explicitEmptySurvivesReopenAndIsNotAnUnconfiguredSource() {
        val backing = InMemoryLineStore()
        val choices = ProviderSelectedModels(backing)
        assertFalse(choices.selection("p").configured)
        choices.write("p", emptyList())
        val reopened = ProviderSelectedModels(backing).selection("p")
        assertTrue(reopened.configured)
        assertTrue(reopened.models.isEmpty())
        assertNull(reopened.defaultModel)
    }

    @Test fun everyProvisioningTypeUsesOnlyExplicitChoicesNotDefaultOrCatalog() {
        ProviderProvisioningKind.entries.forEach { type ->
            val source = row(type)
            assertTrue(source.conversationModels.isEmpty())
            assertFalse(source.offersConversationModels)
            val selected = source.copy(modelSelection = ProviderModelSelection(listOf("b"), "b", true))
            assertEquals(listOf("b"), selected.conversationModels)
            assertEquals(listOf("a", "b", "new"), selected.backendModels)
            assertEquals("a", selected.model)
            assertEquals("b", selected.defaultConversationModel)
            assertTrue(selected.offersConversationModels)
        }
    }

    @Test fun removingDefaultClearsItAndNeverSilentlyEnablesAnotherModel() {
        val choice = ProviderModelSelection().chooseDefault("a").toggle("b", true).toggle("a", false)
        assertEquals(listOf("b"), choice.models)
        assertNull(choice.defaultModel)
        assertTrue(choice.toggle("b", false).models.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { ProviderModelSelection(listOf("a"), "hidden", true) }
    }

    @Test fun customChoicesAndOrderingSurviveHidingAndReopen() {
        val store = ProviderSelectedModels(InMemoryLineStore())
        val value =
            ProviderModelSelection()
                .addCustom("custom/a")
                .addCustom("b")
                .chooseDefault("b")
                .moveFirst("b")
                .toggle("custom/a", false)
        store.save("p", value)
        assertEquals(listOf("b"), store.read("p"))
        assertEquals(listOf("custom/a", "b"), store.selection("p").customModels)
        assertEquals(value, store.selection("p"))
    }

    @Test fun staleDialogCannotOverwriteAnotherEntryPoint() {
        val store = ProviderSelectedModels(InMemoryLineStore())
        val stale = store.selection("p")
        store.save("p", stale.chooseDefault("new"), stale)
        assertThrows(IllegalStateException::class.java) { store.save("p", stale.chooseDefault("old"), stale) }
        assertEquals(listOf("new"), store.read("p"))
    }

    @Test fun invalidInputNeverChangesSavedChoices() {
        val store = ProviderSelectedModels(InMemoryLineStore())
        store.write("p", listOf("a"))
        assertThrows(IllegalArgumentException::class.java) { store.write("p", listOf("line\nbreak")) }
        assertThrows(IllegalArgumentException::class.java) { store.write("p", List(1025) { "a" }) }
        assertEquals(listOf("a"), store.read("p"))
    }

    @Test fun sourceInvalidationClearsEvidenceButNotPreferences() {
        val backing = InMemoryLineStore()
        val status = ProviderTestStatusStore(backing)
        status.selectedModels.write("p", listOf("b"))
        status.modelEvidence.catalog("p", "endpoint", listOf("a", "b"))
        status.clear("p")
        assertEquals(listOf("b"), ProviderTestStatusStore(backing).selectedModels.read("p"))
        assertNull(status.modelEvidence.read("p", "endpoint").catalog)
    }

    @Test fun existingExplicitSubsetIsPreservedWithoutImportingAnEntireCatalog() {
        val backing = InMemoryLineStore()
        backing.setLines("provider-selected-models-p", listOf("b", "c"))
        val store = ProviderSelectedModels(backing)
        assertEquals(listOf("b", "c"), store.read("p"))
        store.write("p", emptyList())
        assertTrue(store.read("p").isEmpty())
        store.clear("p")
        assertFalse(store.selection("p").configured)
    }

    @Test fun localFriendlyNameDoesNotChangeTheWireIdentity() {
        val local =
            row(
                ProviderProvisioningKind.ON_DEVICE_ASSET,
            ).copy(model = "a".repeat(64), displayName = "Local fixture")
        assertEquals("Local fixture", local.modelLabel(local.model))
        assertEquals("a".repeat(64), local.model)
    }

    @Test fun preferenceWritesDoNotInvalidateModelProbeTicketsButConfigurationEditsDo() =
        runBlocking {
            val gate = ProviderProbeGate()
            val selections = ProviderSelectedModels(InMemoryLineStore())
            val a = gate.begin("p", "capabilities:a") {}.first
            val b = gate.begin("p", "capabilities:b") {}.first
            gate.access { selections.write("p", listOf("b")) }
            assertTrue(gate.publish("p", a) {})
            assertTrue(gate.publish("p", b) {})
            gate.mutate("p") {}
            assertFalse(gate.publish("p", a) {})
            assertFalse(gate.publish("p", b) {})
        }

    private fun row(type: ProviderProvisioningKind) =
        ProviderRowUi(
            "p",
            "Source",
            ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
            "https://example.test",
            ProviderResidence.PUBLIC_CLOUD,
            "a",
            false,
            false,
            ConnectionTestStatus.Passed(1, caps, listOf("a", "b", "new")),
            caps,
            listOf("a", "b", "new"),
            emptyList(),
            provisioning = type,
        )

    private val caps = ProviderCapabilities(true, true, false, false, false, false, 8192, CapabilitySource.PROBED)
}
