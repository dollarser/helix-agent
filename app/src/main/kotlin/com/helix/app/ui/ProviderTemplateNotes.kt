package com.helix.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.helix.app.R
import com.helix.provider.catalog.ProviderTemplate

/** Keep catalog configuration independent of Android resources and localize its built-in guidance. */
@Composable
internal fun localizedProviderNotes(template: ProviderTemplate): List<String> {
    val resource =
        when (template.id) {
            "openai" -> R.string.provider_note_openai
            "generic-openai" -> R.string.provider_note_generic
            "ollama" -> R.string.provider_note_ollama
            "sglang" -> R.string.provider_note_sglang
            "openrouter" -> R.string.provider_note_openrouter
            "vllm" -> R.string.provider_note_vllm
            "lm-studio" -> R.string.provider_note_lmstudio
            else -> null
        }
    return if (resource == null) template.notes else listOf(stringResource(resource))
}
