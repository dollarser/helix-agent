package com.helix.app.provider.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Non-secret draft retained by the service editor across configuration changes. */
internal class ProviderModelsEditorState(
    initial: com.helix.app.provider.ProviderModelSelection,
) {
    var baseline by mutableStateOf(initial)
    var selection by mutableStateOf(initial)
    var query by mutableStateOf("")
    var onlySelected by mutableStateOf(false)
    var expanded by mutableStateOf<String?>(null)
    var manual by mutableStateOf("")
    var manualDisplayName by mutableStateOf("")
    var addingModel by mutableStateOf(false)
    var error by mutableStateOf(false)
    var catalogUnsupported by mutableStateOf(false)

    fun matchingModels(candidates: List<String>): List<String> =
        candidates.filter {
            (!onlySelected || it in selection.models) &&
                (it.contains(query, true) || selection.displayNames[it].orEmpty().contains(query, true))
        }

    fun selectionForSave(): com.helix.app.provider.ProviderModelSelection {
        require(manual.isNotBlank() || manualDisplayName.isBlank())
        return if (manual.isBlank()) {
            selection.copy(configured = true)
        } else {
            selection.addCustom(manual.trim()).withDisplayName(manual.trim(), manualDisplayName)
        }
    }

    fun addManual() {
        try {
            selection = selectionForSave()
            manual = ""
            manualDisplayName = ""
            addingModel = false
            error = false
        } catch (_: IllegalArgumentException) {
            error = true
        }
    }
}
