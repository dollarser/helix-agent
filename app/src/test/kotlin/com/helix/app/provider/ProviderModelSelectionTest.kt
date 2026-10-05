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
    @Test fun optionalDisplayNamesPersistWithoutChangingModelIdentity() {
        val backing = InMemoryLineStore()
        val store = ProviderSelectedModels(backing)
        val choice = ProviderModelSelection().addCustom("server-model").withDisplayName("server-model", "  我的模型  ")
        store.save("p", choice)
        val restored = ProviderSelectedModels(backing).selection("p")
        val source = row(ProviderProvisioningKind.USER_CONFIGURED).copy(modelSelection = restored)
        assertEquals("我的模型", source.modelLabel("server-model"))
        assertEquals(listOf("server-model"), source.conversationModels)
        assertTrue(store.selection("other").displayNames.isEmpty())
        store.save("p", restored.withDisplayName("server-model", "   "), expected = restored)
        assertEquals("server-model", source.copy(modelSelection = store.selection("p")).modelLabel("server-model"))
        assertTrue(store.selection("p").displayNames.isEmpty())
    }

    @Test fun onlyLoggedInSubscriptionsAppearInTheModelPicker() {
        val subscription = row(ProviderProvisioningKind.MANAGED_ACCOUNT)
        assertFalse(subscription.visibleInModelPicker)
        ManagedAccountSnapshot.State.entries.forEach { state ->
            val account =
                ManagedAccountSnapshot(
                    state,
                    if (state ==
                        ManagedAccountSnapshot.State.LOGGED_IN
                    ) {
                        "12345678-1234-1234-1234-123456789abc"
                    } else {
                        null
                    },
                )
            assertEquals(
                state == ManagedAccountSnapshot.State.LOGGED_IN,
                subscription.copy(accountState = account).visibleInModelPicker,
            )
        }
        assertTrue(row(ProviderProvisioningKind.USER_CONFIGURED).visibleInModelPicker)
        assertTrue(row(ProviderProvisioningKind.ON_DEVICE_ASSET).visibleInModelPicker)
    }

    @Test fun explicitEmptySurvivesReopenAndIsNotAnUnconfiguredSource() {
        val backing = InMemoryLineStore()
        val choices = ProviderSelectedModels(backing)
        assertFalse(choices.selection("p").configured)
        choices.write("p", emptyList())
        val reopened = ProviderSelectedModels(backing).selection("p")
        assertTrue(reopened.configured)
        assertTrue(reopened.models.isEmpty())
    }

    @Test fun everyProvisioningTypeUsesOnlyExplicitChoicesNotDefaultOrCatalog() {
        ProviderProvisioningKind.entries.forEach { type ->
            val source = row(type)
            assertTrue(source.conversationModels.isEmpty())
            assertFalse(source.offersConversationModels)
            val selected = source.copy(modelSelection = ProviderModelSelection(listOf("b"), configured = true))
            assertEquals(listOf("b"), selected.conversationModels)
            assertEquals(listOf("a", "b", "new"), selected.backendModels)
            assertEquals("a", selected.model)
            assertTrue(selected.offersConversationModels)
        }
    }

    @Test fun removingAChoiceNeverSilentlyEnablesAnotherModel() {
        val choice = ProviderModelSelection().toggle("a", true).toggle("b", true).toggle("a", false)
        assertEquals(listOf("b"), choice.models)
        assertTrue(choice.toggle("b", false).models.isEmpty())
    }

    @Test fun retiredDefaultIsIgnoredAndNoLongerWritten() {
        val backing = InMemoryLineStore()
        backing.setLines("provider-model-selection-v2-p", listOf("""{"models":["b"],"default":"b","custom":[]}"""))
        val store = ProviderSelectedModels(backing)
        assertEquals(listOf("b"), store.selection("p").models)
        store.save("p", store.selection("p"))
        assertFalse(backing.lines("provider-model-selection-v2-p").single().contains("default"))
    }

    @Test fun customChoicesAndOrderingSurviveHidingAndReopen() {
        val store = ProviderSelectedModels(InMemoryLineStore())
        val value =
            ProviderModelSelection()
                .addCustom("custom/a")
                .addCustom("b")
                .toggle("b", true)
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
        store.save("p", stale.toggle("new", true), stale)
        assertThrows(IllegalStateException::class.java) { store.save("p", stale.toggle("old", true), stale) }
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
