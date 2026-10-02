package com.helix.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.provider.ComposeOutcome
import com.helix.app.provider.ProviderComposer
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ProviderService
import com.helix.app.ui.indicatedVerticalScroll
import com.helix.core.model.NormalizedEndpoint
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProviderConfig
import com.helix.provider.catalog.ProviderTemplate
import com.helix.provider.catalog.ProviderTemplateCatalog

/** The un-persisted provider form (create when [providerId] is null). */
internal data class ProviderForm(
    val providerId: String?,
    val template: ProviderTemplate,
    val fields: FormFields,
    val hasStoredKey: Boolean,
    val error: SaveResult.Rejected?,
    val selectedModels: Set<String> = emptySet(),
    val preservedHeaders: Map<String, String> = emptyMap(),
    val protocol: com.helix.core.model.ProviderProtocol = template.protocol,
) {
    data class FormFields(
        val name: String,
        val endpoint: String,
        val model: String,
        val headerName: String,
        val headerValue: String,
        val apiKey: String,
    )
}

/**
 * The outcome of a save attempt. [SaveResult.Rejected] carries a STABLE string-resource id +
 * args, never locale text (HXA-069: the non-composable save path holds no Context — the dialog
 * resolves the id via `stringResource`).
 */
internal sealed interface SaveResult {
    data object Saved : SaveResult

    data class Rejected(
        val res: Int,
        val args: List<String> = emptyList(),
    ) : SaveResult
}

@Composable
@Suppress("FunctionName")
internal fun TemplatePickerDialog(
    onSelect: (ProviderTemplate) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.provider_template_picker_title)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .heightIn(max = 400.dp)
                        .indicatedVerticalScroll(rememberScrollState()),
            ) {
                ProviderTemplateCatalog.all.forEach { template ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(template) }
                                .testTag("provider-template-${template.id}")
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(template.displayName, style = MaterialTheme.typography.bodyLarge)
                            val credentialNote = stringResource(R.string.provider_template_key_optional)
                            Text(
                                "${UiLabels.protocolLabel(template.protocol)} · $credentialNote",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
        modifier = Modifier.testTag("provider-template-picker"),
    )
}

// The Compose DSL keeps the whole form (template fields, endpoint parse
// feedback, key entry, the cleartext risk box, the save gate) in one
// composable; detekt's size/complexity rules do not model UI composition,
// so both are suppressed per composable (same convention as the app shell).
@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod")
internal fun ProviderFormDialog(
    form: ProviderForm,
    saving: Boolean,
    onField: (ProviderForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    discoveryState: ProviderFormDiscovery = ProviderFormDiscovery(),
    onDiscover: () -> Unit = {},
) {
    val discovery = discoveryState.models
    val discovering = discoveryState.running
    val discoveryMessage = discoveryState.message
    var advanced by remember { mutableStateOf(false) }
    var protocolsOpen by remember { mutableStateOf(false) }
    val cleartext =
        remember(form.fields.endpoint) {
            tryParseEndpoint(form.fields.endpoint)?.let { CleartextWarning.forEndpoint(it) }
        }
    val saveEnabled = !saving && !discovering
    var saveAttempt by remember { mutableStateOf(0) }
    val scroll = rememberScrollState()
    val locations = remember { ProviderFormField.entries.associateWith { BringIntoViewRequester() } }
    val focuses = remember { ProviderFormField.entries.associateWith { FocusRequester() } }
    val invalidField = providerErrorField(form.error)

    fun fieldModifier(
        field: ProviderFormField,
        tag: String,
    ): Modifier =
        Modifier
            .bringIntoViewRequester(locations.getValue(field))
            .focusRequester(focuses.getValue(field))
            .testTag(tag)
    LaunchedEffect(form.error, advanced, saveAttempt) {
        val target = invalidField ?: return@LaunchedEffect
        if (target == ProviderFormField.MODEL && form.providerId != null) return@LaunchedEffect
        if (target in setOf(ProviderFormField.HEADER_NAME, ProviderFormField.HEADER_VALUE) && !advanced) {
            advanced = true
        } else {
            withFrameNanos { }
            locations.getValue(target).bringIntoView()
            focuses.getValue(target).requestFocus()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (form.providerId == null) R.string.provider_add else R.string.provider_edit,
                ),
            )
        },
        text = {
            Column(Modifier.heightIn(max = 440.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(
                    modifier =
                        Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .indicatedVerticalScroll(scroll)
                            .testTag("provider-form-scroll")
                            .padding(end = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (form.providerId == null) {
                        localizedProviderNotes(form.template).forEach { note ->
                            Text(
                                stringResource(R.string.provider_template_note, note),
                                modifier = Modifier.testTag("provider-template-guidance"),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    OutlinedTextField(
                        value = form.fields.name,
                        onValueChange = { onField(form.copy(fields = form.fields.copy(name = it))) },
                        label = { Text(stringResource(R.string.provider_form_name)) },
                        singleLine = true,
                        isError = invalidField == ProviderFormField.NAME,
                        modifier = fieldModifier(ProviderFormField.NAME, "provider-form-name"),
                    )
                    androidx.compose.foundation.layout.Box {
                        TextButton(
                            onClick = { protocolsOpen = true },
                            enabled = !saving && !discovering,
                            modifier = Modifier.testTag("provider-form-protocol"),
                        ) {
                            Text(
                                stringResource(
                                    R.string.provider_form_protocol,
                                    UiLabels.protocolLabel(form.protocol),
                                ),
                            )
                        }
                        androidx.compose.material3.DropdownMenu(protocolsOpen, { protocolsOpen = false }) {
                            listOf(
                                com.helix.core.model.ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                                com.helix.core.model.ProviderProtocol.OPENAI_RESPONSES,
                                com.helix.core.model.ProviderProtocol.ANTHROPIC_MESSAGES,
                            ).forEach { protocol ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(UiLabels.protocolLabel(protocol)) },
                                    onClick = {
                                        protocolsOpen = false
                                        onField(form.copy(protocol = protocol, selectedModels = emptySet()))
                                    },
                                    modifier = Modifier.testTag("provider-protocol-${protocol.name}"),
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = form.fields.endpoint,
                        onValueChange = { onField(form.copy(fields = form.fields.copy(endpoint = it))) },
                        label = { Text(stringResource(R.string.provider_form_endpoint_label)) },
                        singleLine = true,
                        isError = invalidField == ProviderFormField.ENDPOINT,
                        modifier = fieldModifier(ProviderFormField.ENDPOINT, "provider-form-endpoint"),
                    )
                    OutlinedTextField(
                        value = form.fields.apiKey,
                        onValueChange = {
                            onField(form.copy(fields = form.fields.copy(apiKey = it)))
                        },
                        label = {
                            Text(
                                if (form.hasStoredKey) {
                                    stringResource(R.string.provider_form_api_key_keep)
                                } else {
                                    stringResource(R.string.provider_key_optional_label)
                                },
                            )
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.testTag("provider-form-key"),
                    )
                    TextButton(
                        onClick = { advanced = !advanced },
                        modifier = Modifier.testTag("provider-form-advanced"),
                    ) {
                        Text(stringResource(R.string.provider_advanced_options))
                    }
                    if (advanced) {
                        OutlinedTextField(
                            value = form.fields.headerName,
                            onValueChange = {
                                onField(form.copy(fields = form.fields.copy(headerName = it)))
                            },
                            label = { Text(stringResource(R.string.provider_form_header_name_label)) },
                            singleLine = true,
                            isError = invalidField == ProviderFormField.HEADER_NAME,
                            modifier =
                                fieldModifier(
                                    ProviderFormField.HEADER_NAME,
                                    "provider-form-header-name",
                                ),
                        )
                        OutlinedTextField(
                            value = form.fields.headerValue,
                            onValueChange = {
                                onField(form.copy(fields = form.fields.copy(headerValue = it)))
                            },
                            label = { Text(stringResource(R.string.provider_form_header_value_label)) },
                            singleLine = true,
                            isError = invalidField == ProviderFormField.HEADER_VALUE,
                            modifier =
                                fieldModifier(
                                    ProviderFormField.HEADER_VALUE,
                                    "provider-form-header-value",
                                ),
                        )
                    }
                    if (form.providerId == null) {
                        OutlinedTextField(
                            value = form.fields.model,
                            onValueChange = { onField(form.copy(fields = form.fields.copy(model = it))) },
                            label = { Text(stringResource(R.string.provider_form_model_label)) },
                            singleLine = true,
                            isError = invalidField == ProviderFormField.MODEL,
                            modifier = fieldModifier(ProviderFormField.MODEL, "provider-form-model"),
                        )
                        TextButton(
                            onClick = onDiscover,
                            enabled = !discovering && !saving,
                            modifier = Modifier.testTag("provider-discover-models"),
                        ) {
                            val label =
                                if (discovering) {
                                    R.string.provider_discovering_models
                                } else {
                                    R.string.provider_discover_models
                                }
                            Text(stringResource(label))
                        }
                        discoveryMessage?.let { Text(stringResource(it)) }
                        discovery.forEach { model ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = model in form.selectedModels,
                                    onCheckedChange = { checked ->
                                        val selected =
                                            if (checked) {
                                                form.selectedModels + model
                                            } else {
                                                form.selectedModels - model
                                            }
                                        val target =
                                            form.fields.model.takeIf { it in selected }
                                                ?: selected.firstOrNull().orEmpty()
                                        val fields = form.fields.copy(model = target)
                                        onField(form.copy(selectedModels = selected, fields = fields))
                                    },
                                    modifier = Modifier.testTag("provider-model-choice-$model"),
                                )
                                Text(model, Modifier.weight(1f))
                            }
                        }
                    } else {
                        Text(stringResource(R.string.provider_models_source_settings_hint))
                    }
                    if (cleartext != null) {
                        Text(
                            stringResource(
                                R.string.provider_cleartext_warning,
                                cleartext.host,
                                cleartext.port,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.testTag("provider-cleartext-warning"),
                        )
                    }
                }
                if (scroll.canScrollForward) {
                    Text(
                        stringResource(R.string.provider_form_scroll_more),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("provider-form-scroll-hint"),
                    )
                }
                form.error?.let { error ->
                    Text(
                        localizedString(error.res, error.args),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier =
                            Modifier
                                .testTag("provider-form-error")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    saveAttempt++
                    onSave()
                },
                enabled = saveEnabled,
                modifier = Modifier.testTag("provider-form-save"),
            ) {
                Text(
                    stringResource(
                        if (saving) R.string.provider_save_saving else R.string.provider_save,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("provider-form-cancel"),
            ) {
                Text(stringResource(R.string.common_cancel))
            }
        },
        modifier = Modifier.testTag("provider-form-dialog"),
    )
}

internal fun editingProviderForm(
    row: ProviderRowUi,
    config: ProviderConfig,
    modelOverride: String?,
) = ProviderForm(
    providerId = row.id,
    template = editTemplateFor(config, row.hasKey),
    fields =
        ProviderForm.FormFields(
            name = config.displayName,
            endpoint = config.endpoint.full,
            model = modelOverride ?: config.model,
            headerName =
                config.headers.entries
                    .firstOrNull()
                    ?.key
                    .orEmpty(),
            headerValue =
                config.headers.entries
                    .firstOrNull()
                    ?.value
                    .orEmpty(),
            apiKey = "",
        ),
    hasStoredKey = row.hasKey,
    error = null,
    selectedModels = row.selectedModels.toSet(),
    preservedHeaders =
        config.headers.entries
            .drop(1)
            .associate { it.key to it.value },
)

/**
 * The parse failure is INTENTIONALLY converted to null: the form only needs a
 * cleartext hint for a parseable endpoint; unparseable input is rejected later
 * by the composer with its user-visible reason (doc 02 section 13).
 */
@Suppress("SwallowedException")
private fun tryParseEndpoint(raw: String): NormalizedEndpoint? =
    try {
        NormalizedEndpoint.parse(raw)
    } catch (e: IllegalArgumentException) {
        null
    }

/**
 * The service `require(...)` failures are deliberately converted into the
 * form's error text: the dialog stays open so the user can correct the input,
 * and the internal (English) exception message is never shown raw (doc 02
 * section 13).
 */
@Suppress("SwallowedException", "TooGenericExceptionCaught")
internal suspend fun attemptSave(
    form: ProviderForm,
    providerService: ProviderService,
): SaveResult =
    try {
        validateProviderForm(form) ?: applySave(form, providerService)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // Storage/Keystore failures are not missing fields; never show credentials or raw exception bodies.
        SaveResult.Rejected(R.string.provider_save_failed)
    }

/**
 * Composes + validates the form, then persists (single fail-closed result).
 * The persist calls are the service's suspend Room/Keystore operations; this
 * runs on the caller's coroutine, which the UI scope hops into the service's
 * IO scope for ([ProviderService.create]/[ProviderService.update]).
 */
private suspend fun applySave(
    form: ProviderForm,
    providerService: ProviderService,
): SaveResult {
    val headers =
        form.preservedHeaders +
            if (form.fields.headerName.isNotBlank()) {
                mapOf(form.fields.headerName.trim() to form.fields.headerValue.trim())
            } else {
                emptyMap()
            }
    val outcome =
        ProviderComposer.compose(
            form.template.copy(protocol = form.protocol, credentialRequired = false, defaultHeaders = emptyMap()),
            form.fields.name.trim(),
            form.fields.endpoint.trim(),
            form.fields.model
                .trim()
                .ifEmpty { form.selectedModels.firstOrNull().orEmpty() },
            headers,
        )
    return when (outcome) {
        is ComposeOutcome.Rejected -> {
            SaveResult.Rejected(outcome.reasonRes, outcome.reasonArgs)
        }

        is ComposeOutcome.Ok -> {
            val draft = outcome.draft
            val key =
                form.fields.apiKey
                    .trim()
                    .takeIf { it.isNotEmpty() }
            when {
                draft.credentialRequired && key == null && !form.hasStoredKey -> {
                    SaveResult.Rejected(R.string.provider_requires_api_key)
                }

                else -> {
                    val models =
                        if (form.selectedModels.isEmpty()) listOf(draft.model) else form.selectedModels.toList()
                    com.helix.app.provider.ProviderSelectedModels
                        .validate(models)
                    if (form.providerId == null) {
                        require(draft.model in models) { "The default must be one of the selected models" }
                        val id = providerService.create(draft, key)
                        providerService.saveModelSelection(
                            id,
                            com.helix.app.provider
                                .ProviderModelSelection(models, configured = true),
                        )
                    } else {
                        // Connection edits never overwrite model visibility/default preferences.
                        providerService.update(form.providerId, draft, key)
                    }
                    SaveResult.Saved
                }
            }
        }
    }
}
