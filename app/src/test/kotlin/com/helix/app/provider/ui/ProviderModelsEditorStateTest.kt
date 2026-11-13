package com.helix.app.provider.ui

import com.helix.app.provider.ProviderModelSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderModelsEditorStateTest {
    @Test fun wholePageSaveIncludesPendingTypedModelWithoutChangingDraft() {
        val draft = ProviderModelsEditorState(ProviderModelSelection(listOf("first"), configured = true))
        draft.manual = " second "
        draft.manualDisplayName = "Second model"
        val snapshot = draft.selectionForSave()
        assertEquals(listOf("first", "second"), snapshot.models)
        assertEquals("Second model", snapshot.displayNames["second"])
        assertEquals(listOf("first"), draft.selection.models)
        assertEquals(" second ", draft.manual)
    }

    @Test fun sharedAdderSupportsRepeatedModelsAndRetainsInvalidInput() {
        val draft = ProviderModelsEditorState(ProviderModelSelection())
        draft.manual = "first"
        draft.addManual()
        assertEquals(listOf("first"), draft.selection.models)
        assertEquals("", draft.manual)
        assertFalse(draft.error)
        draft.manual = "second"
        draft.addManual()
        assertEquals(listOf("first", "second"), draft.selection.models)
        draft.manual = "invalid model"
        draft.addManual()
        assertTrue(draft.error)
        assertEquals("invalid model", draft.manual)
        assertEquals(listOf("first", "second"), draft.selection.models)
    }
}
