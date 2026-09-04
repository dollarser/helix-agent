package com.helix.app.ui

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.helix.app.R
import com.helix.app.provider.ComposeOutcome
import com.helix.app.provider.ConnectionTestMapping
import com.helix.app.provider.ConnectionTestStatus
import com.helix.app.provider.ProviderComposer
import com.helix.app.provider.ProviderRowUi
import com.helix.app.provider.ProviderService
import com.helix.core.model.NormalizedEndpoint
import com.helix.core.model.ProviderProtocol
import com.helix.provider.api.CleartextAuthorization
import com.helix.provider.catalog.ProviderTemplate
import com.helix.provider.catalog.ProviderTemplateCatalog
import kotlinx.coroutines.launch

/**
 * Provider management UI (HXA-028; the task's “设置 → Provider” section).
 *
 * The UI dispatches intents to [ProviderService] and observes its [rows]
 * StateFlow — it never holds a network Job, never touches DAOs/OkHttp and
 * never sees a secret (only the [ProviderRowUi.hasKey] flag, NFR-007).
 *
 * Rules rendered here (all enforced in the pure/service layer):
 * - a provider is chat-selectable ONLY after a completed connection test
 *   (“未完成连接测试不贬为已可用”);
 * - a cleartext http endpoint shows the host:port risk display and requires
 *   the explicit per-host:port confirmation checkbox before save
 *   (doc 10 section 2.5; ADR-0005: no global cleartext switch);
 * - a test failure shows the SAFE phase + code label (FR-LLM-004 / doc 02
 *   section 13) — never a raw exception message.
 */
@Composable
@Suppress("FunctionName", "LongMethod", "TooGenericExceptionCaught")
fun ProviderManager(providerService: ProviderService) {
    val rows by providerService.rows.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var templatePickerOpen by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<ProviderForm?>(null) }
    var testingId by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.provider_screen_title), style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = { templatePickerOpen = true },
                modifier = Modifier.testTag("provider-add"),
            ) {
                Text(stringResource(R.string.provider_add))
            }
        }
        if (rows.isEmpty()) {
            Text(
                stringResource(R.string.provider_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rows.forEach { row ->
            ProviderRow(
                row = row,
                testing = testingId == row.id,
                visionEnabled = row.capabilities?.vision == true,
                onDeclareVision = { enabled ->
                    // The user-visible manual declaration (ADR-0014): vision may come from a
                    // real probe OR this explicit mark — the UI shows 「手动声明」 afterwards.
                    scope.launch { providerService.declareVisionCapability(row.id, enabled) }
                },
                onTest = {
                    if (testingId == null) {
                        testingId = row.id
                        scope.launch {
                            try {
                                providerService.runConnectionTest(row.id)
                            } catch (e: Exception) {
                                // A row that cannot even be resolved (corruption) fails
                                // closed: the status stays 未测试, the row stays
                                // non-selectable, and the exception is logged — never
                                // rendered raw (doc 02 section 13).
                                Log.w(TAG, "connection test for ${row.id} did not run", e)
                            } finally {
                                testingId = null
                            }
                        }
                    }
                },
                onEdit = {
                    // storedConfig is a Room read: it runs on the service's IO
                    // scope, never on this (UI) thread.
                    scope.launch {
                        try {
                            val config = providerService.storedConfig(row.id)
                            form =
                                ProviderForm(
                                    providerId = row.id,
                                    template = editTemplateFor(config.protocol, config.endpoint),
                                    fields =
                                        ProviderForm.FormFields(
                                            name = config.displayName,
                                            endpoint = config.endpoint.full,
                                            model = config.model,
                                            headerName = "",
                                            headerValue = "",
                                            apiKey = "",
                                        ),
                                    hasStoredKey = row.hasKey,
                                    cleartextConfirmed = false,
                                    error = null,
                                )
                        } catch (e: Exception) {
                            // A row that cannot be resolved (corruption) fails
                            // closed: the edit is not opened and the exception
                            // is logged — never rendered raw (doc 02 section 13).
                            Log.w(TAG, "could not load provider ${row.id} for edit", e)
                        }
                    }
                },
                onDelete = {
                    scope.launch {
                        try {
                            providerService.delete(row.id)
                        } catch (e: Exception) {
                            Log.w(TAG, "could not delete provider ${row.id}", e)
                        }
                    }
                },
            )
        }
    }

    if (templatePickerOpen) {
        TemplatePickerDialog(
            onSelect = { template ->
                templatePickerOpen = false
                form =
                    ProviderForm(
                        providerId = null,
                        template = template,
                        fields =
                            ProviderForm.FormFields(
                                name = template.displayName,
                                endpoint = template.defaultEndpoint?.full.orEmpty(),
                                model = "",
                                headerName = "",
                                headerValue = "",
                                apiKey = "",
                            ),
                        hasStoredKey = false,
                        cleartextConfirmed = false,
                        error = null,
                    )
            },
            onDismiss = { templatePickerOpen = false },
        )
    }

    val currentForm = form
    if (currentForm != null) {
        ProviderFormDialog(
            form = currentForm,
            saving = saving,
            onField = { form = it },
            onDismiss = {
                // An in-flight save keeps running (its result is dropped below
                // when it no longer matches the form); only the dialog closes.
                form = null
            },
            onSave = {
                if (saving) return@ProviderFormDialog
                val target = currentForm
                saving = true
                scope.launch {
                    try {
                        val result = attemptSave(target, providerService)
                        // Stale-result guard: the form may have changed (or been
                        // closed) while the Room/Keystore write was in flight.
                        if (form == target) {
                            form =
                                when (result) {
                                    SaveResult.Saved -> null
                                    is SaveResult.Rejected -> target.copy(error = result)
                                }
                        }
                    } finally {
                        saving = false
                    }
                }
            },
        )
    }
}

/** The un-persisted provider form (create when [providerId] is null). */
private data class ProviderForm(
    val providerId: String?,
    val template: ProviderTemplate,
    val fields: FormFields,
    val hasStoredKey: Boolean,
    val cleartextConfirmed: Boolean,
    val error: SaveResult.Rejected?,
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
private sealed interface SaveResult {
    data object Saved : SaveResult

    data class Rejected(
        val res: Int,
        val args: List<String> = emptyList(),
    ) : SaveResult
}

@Composable
@Suppress("FunctionName")
private fun TemplatePickerDialog(
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
                        .verticalScroll(rememberScrollState())
                        .heightIn(max = 400.dp),
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
                            val credentialNote =
                                if (template.credentialRequired) {
                                    stringResource(R.string.provider_template_requires_key)
                                } else {
                                    stringResource(R.string.provider_template_key_optional)
                                }
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
private fun ProviderFormDialog(
    form: ProviderForm,
    saving: Boolean,
    onField: (ProviderForm) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cleartext =
        remember(form.fields.endpoint) {
            tryParseEndpoint(form.fields.endpoint)?.let { CleartextAuthorization.requiredFor(it) }
        }
    val keyOk =
        !form.template.credentialRequired ||
            form.fields.apiKey.isNotBlank() ||
            form.hasStoredKey
    val saveEnabled =
        !saving && form.error == null && (cleartext == null || form.cleartextConfirmed) && keyOk
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (form.providerId == null) {
                    form.template.notes.forEach { note ->
                        Text(
                            stringResource(R.string.provider_template_note, note),
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
                    modifier = Modifier.testTag("provider-form-name"),
                )
                OutlinedTextField(
                    value = form.fields.endpoint,
                    onValueChange = { onField(form.copy(fields = form.fields.copy(endpoint = it))) },
                    label = { Text(stringResource(R.string.provider_form_endpoint_label)) },
                    singleLine = true,
                    modifier = Modifier.testTag("provider-form-endpoint"),
                )
                OutlinedTextField(
                    value = form.fields.model,
                    onValueChange = { onField(form.copy(fields = form.fields.copy(model = it))) },
                    label = { Text(stringResource(R.string.provider_form_model_label)) },
                    singleLine = true,
                    modifier = Modifier.testTag("provider-form-model"),
                )
                OutlinedTextField(
                    value = form.fields.headerName,
                    onValueChange = {
                        onField(form.copy(fields = form.fields.copy(headerName = it)))
                    },
                    label = { Text(stringResource(R.string.provider_form_header_name_label)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = form.fields.headerValue,
                    onValueChange = {
                        onField(form.copy(fields = form.fields.copy(headerValue = it)))
                    },
                    label = { Text(stringResource(R.string.provider_form_header_value_label)) },
                    singleLine = true,
                )
                if (form.template.credentialRequired) {
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
                                    "API Key"
                                },
                            )
                        },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.testTag("provider-form-key"),
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
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = form.cleartextConfirmed,
                            onCheckedChange = { onField(form.copy(cleartextConfirmed = it)) },
                            modifier = Modifier.testTag("provider-cleartext-confirm"),
                        )
                        Text(
                            stringResource(R.string.provider_cleartext_confirm),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                form.error?.let { error ->
                    Text(
                        localizedString(error.res, error.args),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSave,
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

@Composable
@Suppress("FunctionName", "LongMethod")
private fun ProviderRow(
    row: ProviderRowUi,
    testing: Boolean,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    visionEnabled: Boolean,
    onDeclareVision: (enabled: Boolean) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag("provider-row")
                .padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                row.displayName,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            StatusChip(row.status)
        }
        Text(
            "${UiLabels.displayOrigin(row.origin)} · ${stringResource(UiLabels.residenceLabelRes(row.residence))}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            buildString {
                append(
                    stringResource(
                        R.string.provider_row_model,
                        row.model,
                        UiLabels.protocolLabel(row.protocol),
                    ),
                )
                if (row.hasKey) append(stringResource(R.string.provider_row_has_key))
                if (row.isCleartext) append(stringResource(R.string.provider_row_cleartext))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val detail = statusDetail(row)
        if (detail != null) {
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onTest,
                enabled = !testing,
                modifier = Modifier.testTag("provider-test"),
            ) {
                Text(
                    stringResource(
                        if (testing) R.string.provider_testing else R.string.provider_connection_test,
                    ),
                )
            }
            TextButton(onClick = onEdit, modifier = Modifier.testTag("provider-edit")) {
                Text(stringResource(R.string.provider_edit_button))
            }
            TextButton(
                onClick = { onDeclareVision(!visionEnabled) },
                modifier = Modifier.testTag("provider-vision-declare"),
            ) {
                Text(
                    stringResource(
                        if (visionEnabled) {
                            R.string.provider_vision_declare_off
                        } else {
                            R.string.provider_vision_declare_on
                        },
                    ),
                )
            }
            TextButton(
                onClick = onDelete,
                modifier = Modifier.testTag("provider-delete"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.provider_delete))
            }
        }
    }
}

@Composable
@Suppress("FunctionName")
private fun StatusChip(status: ConnectionTestStatus) {
    val (color, tag) =
        when (status) {
            ConnectionTestStatus.Untested -> {
                MaterialTheme.colorScheme.surfaceVariant to "provider-status-untested"
            }

            is ConnectionTestStatus.Passed -> {
                MaterialTheme.colorScheme.primaryContainer to "provider-status-passed"
            }

            is ConnectionTestStatus.Failed -> {
                MaterialTheme.colorScheme.errorContainer to "provider-status-failed"
            }
        }
    val label =
        when (status) {
            ConnectionTestStatus.Untested -> {
                stringResource(R.string.conn_untested)
            }

            is ConnectionTestStatus.Passed -> {
                stringResource(R.string.conn_passed)
            }

            is ConnectionTestStatus.Failed -> {
                stringResource(
                    R.string.conn_failed_phase,
                    stringResource(ConnectionTestMapping.phaseLabel(status.phase)),
                )
            }
        }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color,
        modifier = Modifier.testTag(tag),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * The status detail line under a provider row (safe labels only). Composable because it resolves
 * the stable resource ids to the current locale (HXA-069); the phase/code labels are SAFE mapper
 * output, never raw exception text (doc 02 section 13).
 */
@Composable
private fun statusDetail(row: ProviderRowUi): String? =
    when (val status = row.status) {
        ConnectionTestStatus.Untested -> {
            stringResource(R.string.provider_untested_detail)
        }

        is ConnectionTestStatus.Passed -> {
            if (row.capabilityChips.isEmpty()) {
                null
            } else {
                // Compose: resolve each capability chip in a `for` loop (composable scope); a
                // `joinToString` transform lambda is not composable and would not compile.
                val chips = mutableListOf<String>()
                for (chip in row.capabilityChips) {
                    chips.add(localizedString(chip.res, chip.args))
                }
                stringResource(
                    R.string.provider_capability_detail,
                    chips.joinToString("  "),
                )
            }
        }

        is ConnectionTestStatus.Failed -> {
            stringResource(
                R.string.provider_failed_detail,
                stringResource(ConnectionTestMapping.phaseLabel(status.phase)),
                stringResource(ConnectionTestMapping.codeLabel(status.code)),
                if (status.retryable) stringResource(R.string.provider_retryable) else "",
            )
        }
    }

/**
 * The template an edit dialog composes against. The persisted row does not
 * store the template id, so the template is re-resolved from the protocol: an
 * exact endpoint match (same template family) wins, then the generic (no
 * default endpoint) template of the protocol, then any template of the
 * protocol. For a re-pointed endpoint the generic template's headers replace
 * the old attribution headers — the honest outcome, since the endpoint is no
 * longer that vendor's.
 */
private fun editTemplateFor(
    protocol: ProviderProtocol,
    endpoint: NormalizedEndpoint,
): ProviderTemplate {
    val candidates = ProviderTemplateCatalog.all.filter { it.protocol == protocol }
    return candidates.firstOrNull { it.defaultEndpoint == endpoint }
        ?: candidates.firstOrNull { it.defaultEndpoint == null }
        ?: candidates.first()
}

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
@Suppress("SwallowedException")
private suspend fun attemptSave(
    form: ProviderForm,
    providerService: ProviderService,
): SaveResult =
    try {
        applySave(form, providerService)
    } catch (e: IllegalArgumentException) {
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
        if (form.fields.headerName.isNotBlank()) {
            mapOf(form.fields.headerName.trim() to form.fields.headerValue.trim())
        } else {
            emptyMap()
        }
    val outcome =
        ProviderComposer.compose(
            form.template,
            form.fields.name.trim(),
            form.fields.endpoint.trim(),
            form.fields.model.trim(),
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

                draft.isCleartext && !form.cleartextConfirmed -> {
                    SaveResult.Rejected(R.string.provider_cleartext_confirm_required)
                }

                else -> {
                    if (form.providerId == null) {
                        providerService.create(draft, key, form.cleartextConfirmed)
                    } else {
                        providerService.update(form.providerId, draft, key, form.cleartextConfirmed)
                    }
                    SaveResult.Saved
                }
            }
        }
    }
}

private const val TAG = "HelixProviderUi"
