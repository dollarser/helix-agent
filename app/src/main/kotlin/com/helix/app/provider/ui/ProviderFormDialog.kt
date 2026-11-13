package com.helix.app.provider.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.helix.app.R
import com.helix.app.provider.ComposeOutcome
import com.helix.app.provider.ProviderComposer
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ProviderService
import com.helix.core.model.NormalizedEndpoint
import com.helix.provider.api.CleartextWarning
import com.helix.provider.api.ProviderConfig
import com.helix.provider.catalog.ProviderTemplate
import com.helix.ui.shared.IndicatedLazyColumn
import com.helix.ui.shared.UiLabels
import com.helix.ui.shared.indicatedVerticalScroll
import com.helix.ui.shared.localizedString
import com.helix.ui.shared.R as SharedR

/** The un-persisted provider form (create when [providerId] is null). */
internal data class ProviderForm(
    val providerId: String?,
    val template: ProviderTemplate,
    val fields: FormFields,
    val hasStoredKey: Boolean,
    val error: SaveResult.Rejected?,
    val preservedHeaders: Map<String, String> = emptyMap(),
    val protocol: com.helix.core.model.ProviderProtocol = template.protocol,
    val savedConfig: ProviderConfig? = null,
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
                providerTemplateChoices().forEach { source ->
                    val template =
                        if (source.id == "generic-openai") {
                            source.copy(displayName = stringResource(R.string.provider_custom_service))
                        } else {
                            source
                        }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(template) }
                                .testTag("provider-template-${template.id}")
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(template.displayName, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(SharedR.string.common_cancel)) }
        },
        modifier = Modifier.testTag("provider-template-picker"),
    )
}

// The Compose DSL keeps the whole form (template fields, endpoint parse
// feedback, key entry, the cleartext risk box, the save gate) in one
// composable; detekt's size/complexity rules do not model UI composition,
// so both are suppressed per composable (same convention as the app shell).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionName", "LongMethod", "CyclomaticComplexMethod", "LongParameterList")
internal fun ProviderFormDialog(
    form: ProviderForm,
    saving: Boolean,
    onField: (ProviderForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    discoveryState: ProviderFormDiscovery = ProviderFormDiscovery(),
    onDiscover: () -> Unit = {},
    loadStoredKey: suspend (String) -> String? = { null },
    models: ProviderModelsEditorState =
        remember(form.providerId) {
            ProviderModelsEditorState(
                com.helix.app.provider
                    .ProviderModelSelection(),
            ).apply {
                manual = form.fields.model
                addingModel = form.providerId == null
            }
        },
    modelRow: ProviderRowUi? = null,
    modelBusy: Boolean = false,
    onContext: (String) -> Unit = {},
    onTest: (String) -> Unit = {},
    onProbe: (String) -> Unit = {},
    onDeclareVision: ((Boolean) -> Unit)? = null,
) {
    val discovering = discoveryState.running
    var advanced by remember { mutableStateOf(false) }
    val cleartext =
        remember(form.fields.endpoint) {
            tryParseEndpoint(form.fields.endpoint)?.let { CleartextWarning.forEndpoint(it) }
        }
    val saveEnabled = !saving && !discovering && !modelBusy
    var saveAttempt by remember { mutableStateOf(0) }
    val scroll = rememberLazyListState()
    val locations = remember { ProviderFormField.entries.associateWith { BringIntoViewRequester() } }
    val focuses = remember { ProviderFormField.entries.associateWith { FocusRequester() } }
    val invalidField = providerErrorField(form.error)

    fun fieldModifier(
        field: ProviderFormField,
        tag: String,
    ): Modifier =
        Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(locations.getValue(field))
            .focusRequester(focuses.getValue(field))
            .testTag(tag)
    LaunchedEffect(form.error, advanced, models.addingModel, saveAttempt) {
        val target = invalidField ?: return@LaunchedEffect
        if (target == ProviderFormField.MODEL && !models.addingModel) {
            models.addingModel = true
        } else if (target in setOf(ProviderFormField.HEADER_NAME, ProviderFormField.HEADER_VALUE) && !advanced) {
            advanced = true
        } else {
            scroll.scrollToItem(if (target == ProviderFormField.MODEL) 2 else 0)
            withFrameNanos { }
            locations.getValue(target).bringIntoView()
            focuses.getValue(target).requestFocus()
        }
    }
    ProviderEditorDialog(
        onDismissRequest = { if (!saving && !modelBusy) onDismiss() },
        title = {
            Column {
                Text(
                    stringResource(
                        if (form.providerId ==
                            null
                        ) {
                            R.string.provider_add
                        } else {
                            R.string.provider_service_settings
                        },
                    ),
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                IndicatedLazyColumn(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .testTag("provider-form-scroll")
                            .padding(end = 10.dp),
                    state = scroll,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item("connection") {
                        ProviderConnectionFields(
                            form,
                            onField,
                            loadStoredKey,
                            !saving && !modelBusy,
                            advanced,
                            { advanced = it },
                            cleartext,
                            ::fieldModifier,
                        )
                    }
                    providerFormModels(
                        models,
                        modelRow,
                        discoveryState,
                        !saveEnabled,
                        form.connectionMatchesSaved() &&
                            com.helix.app.provider.sameProviderEditableConfig(
                                modelRow?.configuration,
                                form.savedConfig,
                            ),
                        fieldModifier(ProviderFormField.MODEL, "provider-model-manual"),
                        onDiscover,
                        onContext,
                        onTest,
                        onProbe,
                        onDeclareVision,
                    )
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
                enabled = !saving && !modelBusy,
                modifier = Modifier.testTag("provider-form-cancel"),
            ) {
                Text(stringResource(SharedR.string.common_cancel))
            }
        },
        modifier = Modifier.testTag("provider-form-dialog"),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("FunctionName", "LongMethod", "LongParameterList")
private fun ProviderConnectionFields(
    form: ProviderForm,
    onField: (ProviderForm) -> Unit,
    loadStoredKey: suspend (String) -> String?,
    enabled: Boolean,
    advanced: Boolean,
    onAdvanced: (Boolean) -> Unit,
    cleartext: CleartextWarning?,
    fieldModifier: (ProviderFormField, String) -> Modifier,
) {
    var protocolsOpen by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val invalidField = providerErrorField(form.error)
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.provider_editor_connection), style = MaterialTheme.typography.titleMedium)
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
                enabled = enabled,
                isError = invalidField == ProviderFormField.NAME,
                modifier = fieldModifier(ProviderFormField.NAME, "provider-form-name"),
            )
            ExposedDropdownMenuBox(
                expanded = protocolsOpen,
                onExpandedChange = { if (enabled) protocolsOpen = it },
            ) {
                OutlinedTextField(
                    value = UiLabels.protocolLabel(form.protocol),
                    onValueChange = {},
                    readOnly = true,
                    enabled = enabled,
                    label = { Text(stringResource(R.string.provider_form_protocol_label)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(protocolsOpen) },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .menuAnchor(
                                androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                enabled,
                            ).testTag("provider-form-protocol"),
                )
                ExposedDropdownMenu(protocolsOpen, { protocolsOpen = false }) {
                    listOf(
                        com.helix.core.model.ProviderProtocol.OPENAI_CHAT_COMPLETIONS,
                        com.helix.core.model.ProviderProtocol.OPENAI_RESPONSES,
                        com.helix.core.model.ProviderProtocol.ANTHROPIC_MESSAGES,
                    ).forEach { protocol ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(UiLabels.protocolLabel(protocol)) },
                            onClick = {
                                protocolsOpen = false
                                onField(form.copy(protocol = protocol))
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
                enabled = enabled,
                isError = invalidField == ProviderFormField.ENDPOINT,
                modifier = fieldModifier(ProviderFormField.ENDPOINT, "provider-form-endpoint"),
            )
            ProviderApiKeyField(form, onField, enabled = enabled, loadStoredKey = loadStoredKey)
            TextButton(
                onClick = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    onAdvanced(!advanced)
                },
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
                    enabled = enabled,
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
                    enabled = enabled,
                    isError = invalidField == ProviderFormField.HEADER_VALUE,
                    modifier =
                        fieldModifier(
                            ProviderFormField.HEADER_VALUE,
                            "provider-form-header-value",
                        ),
                )
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
    }
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
    savedConfig = config,
    error = null,
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
    models: ProviderModelsEditorState,
    session: com.helix.app.provider.ProviderDraftSaveSession,
    providerService: ProviderService,
): SaveResult =
    try {
        val selection = models.selectionForSave()
        val target =
            if (form.providerId == null) {
                form.copy(fields = form.fields.copy(model = selection.models.firstOrNull().orEmpty()))
            } else {
                form
            }
        val error = validateProviderForm(target)
        if (error != null) {
            error
        } else {
            when (val outcome = composeProviderForm(target)) {
                is ComposeOutcome.Rejected -> {
                    SaveResult.Rejected(outcome.reasonRes, outcome.reasonArgs)
                }

                is ComposeOutcome.Ok -> {
                    providerService.saveDraft(
                        session,
                        outcome.draft,
                        target.fields.apiKey
                            .trim()
                            .takeIf { it.isNotEmpty() },
                        selection,
                    )
                    SaveResult.Saved
                }
            }
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: com.helix.app.provider.ProviderDraftSaveRecoveryRequiredException) {
        SaveResult.Rejected(R.string.provider_save_review_required)
    } catch (_: com.helix.app.provider.ProviderDraftSaveConflictException) {
        SaveResult.Rejected(R.string.provider_save_conflict)
    } catch (_: IllegalArgumentException) {
        models.error = true
        SaveResult.Rejected(R.string.provider_model_selection_invalid)
    } catch (_: Exception) {
        SaveResult.Rejected(R.string.provider_save_failed)
    }

internal fun composeProviderForm(form: ProviderForm): ComposeOutcome =
    ProviderComposer.compose(
        form.template.copy(protocol = form.protocol, credentialRequired = false, defaultHeaders = emptyMap()),
        form.fields.name.trim(),
        form.fields.endpoint.trim(),
        form.fields.model.trim(),
        form.preservedHeaders +
            if (form.fields.headerName.isNotBlank()) {
                mapOf(form.fields.headerName.trim() to form.fields.headerValue.trim())
            } else {
                emptyMap()
            },
    )

/** A display-name or model-selection edit is not a connection change. */
internal fun ProviderForm.connectionMatchesSaved(): Boolean {
    val original = savedConfig ?: return false
    val composed = composeProviderForm(this) as? ComposeOutcome.Ok
    return composed != null &&
        !com.helix.app.provider
            .providerConnectionChanged(original, composed.draft, fields.apiKey)
}
