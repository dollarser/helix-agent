package com.helix.app.ui

import com.helix.app.plugin.PluginName
import com.helix.app.plugin.PluginSessionRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentPluginPreparationTest {
    private val documents =
        PluginSessionRow(
            id = "bundled-plugin:documents",
            pluginName = PluginName("documents", "documents"),
            selected = true,
            defaultSelected = false,
            available = true,
            ready = true,
        )

    @Test fun selectedReadyNativeDocumentsSatisfiesPreparationRegardlessOfLabel() {
        assertTrue(isDocumentPluginReady(documents))
        assertTrue(isDocumentPluginReady(documents.copy(pluginName = PluginName("文档"))))
    }

    @Test fun sameNamedThirdPartyCannotSatisfyNativePreparation() {
        assertFalse(
            isDocumentPluginReady(
                documents.copy(id = "portable-installation", pluginName = PluginName("documents")),
            ),
        )
        assertFalse(isDocumentPluginReady(documents.copy(id = "portable-installation")))
    }

    @Test fun nativeSelectionAndReadinessRemainRequired() {
        assertFalse(isDocumentPluginReady(documents.copy(selected = false)))
        assertFalse(isDocumentPluginReady(documents.copy(ready = false)))
        assertFalse(isDocumentPluginReady(documents.copy(available = false)))
        assertFalse(isDocumentPluginReady(documents.copy(enabled = false)))
    }
}
