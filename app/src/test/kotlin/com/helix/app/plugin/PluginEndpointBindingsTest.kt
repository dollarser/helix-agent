package com.helix.app.plugin

import com.helix.extensions.plugin.PluginEndpoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginEndpointBindingsTest {
    private val original = PluginEndpoint("first", "https://example.com/mcp", true, "a".repeat(64))

    @Test fun sameUrlDoesNotShareAccountBetweenNamedComponents() {
        val old = InstalledEndpoint("original", original)
        val updated =
            PluginEndpointBindings.reconcile(listOf(old), listOf(original, original.copy(name = "second"))) {
                "new-component"
            }
        assertEquals(old, updated.first())
        assertNotEquals(updated.first().id, updated.last().id)
    }

    @Test fun unchangedComponentsKeepIdentityEvenWhenOrderChanges() {
        val second = original.copy(name = "second")
        val old = listOf(InstalledEndpoint("one", original), InstalledEndpoint("two", second))
        val updated =
            PluginEndpointBindings.reconcile(
                old,
                listOf(second, original),
            ) { error("Unexpected new account") }
        assertEquals(listOf("two", "one"), updated.map { it.id })
    }

    @Test fun changedAuthenticationTargetOrComponentNameCreatesNewIdentity() {
        val old = listOf(InstalledEndpoint("old", original))
        listOf(
            original.copy(name = "renamed"),
            original.copy(url = "https://example.com/other"),
            original.copy(authBindingHash = "b".repeat(64)),
        ).forEach { changed ->
            assertEquals("new", PluginEndpointBindings.reconcile(old, listOf(changed)) { "new" }.single().id)
        }
    }

    @Test fun duplicateNamesAndIdsFailBeforePublication() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginEndpointBindings.reconcile(emptyList(), listOf(original, original)) { "id" }
        }
        assertThrows(IllegalArgumentException::class.java) {
            PluginEndpointBindings.reconcile(emptyList(), listOf(original, original.copy(name = "other"))) { "id" }
        }
    }
}
