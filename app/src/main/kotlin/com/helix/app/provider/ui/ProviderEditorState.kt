package com.helix.app.provider.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** Retain an unfinished form across rotation, without serializing credentials into saved state. */
internal class ProviderEditorState(
    private val scope: kotlinx.coroutines.CoroutineScope? = null,
) : ViewModel() {
    var form by mutableStateOf<ProviderForm?>(null)
    var row: com.helix.app.provider.ProviderRowUi? = null
    var models: ProviderModelsEditorState? = null
    var saveSession: com.helix.app.provider.ProviderDraftSaveSession? = null
    var saving by mutableStateOf(false)
        private set

    fun close() {
        if (saving) return
        form = null
        row = null
        models = null
        saveSession = null
    }

    fun save(
        target: ProviderForm,
        persist: suspend (ProviderForm) -> SaveResult,
    ) {
        if (saving || form != target) return
        saving = true
        (scope ?: viewModelScope).launch {
            try {
                val result = persist(target)
                if (form == target) {
                    form =
                        when (result) {
                            SaveResult.Saved -> {
                                row = null
                                models = null
                                saveSession = null
                                null
                            }

                            is SaveResult.Rejected -> {
                                target.copy(error = result)
                            }
                        }
                }
            } finally {
                saving = false
            }
        }
    }
}
